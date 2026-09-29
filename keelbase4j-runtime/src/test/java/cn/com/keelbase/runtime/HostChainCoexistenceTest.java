// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

/**
 * JV-35 F1 — two chains in one process: each governs its own paths, and the process starts.
 *
 * <p>An embedded deployment does not stop having its own security: the host authenticates its own
 * users, protects its own screens and serves its own API, and it does that with a
 * {@code SecurityFilterChain} of its own. So the process holds <b>two</b> chains, and Spring Security
 * picks by {@code @Order} — it has no notion of "the application's own chain".
 *
 * <p><b>What actually happens without one</b> (measured, 2026-09-29 — the seam record had this as a
 * coin flip, which static reading could not settle): the context does not start at all.
 * {@code WebSecurityConfiguration} builds the chains, finds two that each match any request, and
 * refuses with {@code UnreachableFilterChainException} — "this filter chain will never get
 * invoked. Please use {@code HttpSecurity#securityMatcher} to ensure that there is only one filter
 * chain configured for 'any request'". So the embedding does not fail open, nor fail to 401s; it
 * fails to <em>boot</em>, which is why this is the first cut the seam record calls for rather than a
 * detail to leave until the host runs.
 *
 * <p>The host chain here is deliberately left <b>unordered and unscoped</b>, because that is what a
 * host is entitled to look like — F1 assigns the defect to this side, and the host's missing
 * {@code @Order} is its default behaviour, not a bug to report back. The two chains answer with
 * different statuses, so "which one governed this request" is readable from the response alone: the
 * runtime's chain answers 401, this host's answers 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class HostChainCoexistenceTest {

    /** A path this runtime serves, and one only a host would serve. */
    private static final String RUNTIME_PATH = "/ai/tool-effects";

    private static final String HOST_PATH = "/system/user/list";

    /**
     * The host, in miniature: a chain with no {@code @Order} and no path scope, which is exactly the
     * shape F1 found in RuoYi. It refuses with 403 so its answers are distinguishable from the
     * runtime chain's 401.
     */
    @TestConfiguration
    static class HostOwnsItsOwnChain {

        @Bean
        SecurityFilterChain hostChain(HttpSecurity http) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .exceptionHandling(handling -> handling.authenticationEntryPoint(
                            (request, response, denied) -> response.sendError(
                                    HttpServletResponse.SC_FORBIDDEN, "the host chain refused")));
            return http.build();
        }
    }

    @Autowired
    TestRestTemplate rest;

    /** The runtime's own path is governed by the runtime's chain. */
    @Test
    void theRuntimesPathsAreGovernedByTheRuntimesChain() {
        assertEquals(401, get(RUNTIME_PATH),
                "the runtime's chain answers for the paths the runtime serves");
    }

    /**
     * The host's path is governed by the host's chain — the assertion that goes red while the
     * runtime's chain still ends in an unscoped {@code anyRequest()}.
     */
    @Test
    void theHostsPathsAreLeftToTheHostsChain() {
        assertEquals(403, get(HOST_PATH),
                "a path this runtime does not serve is not this runtime's to refuse");
    }

    private int get(String path) {
        return rest.exchange(URI.create(path), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class).getStatusCode().value();
    }
}
