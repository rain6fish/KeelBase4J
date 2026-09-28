// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS, so a browser-hosted frontend can reach this runtime.
 *
 * <p>Without it the runtime is only callable from a server: a browser sends a preflight
 * {@code OPTIONS} first, and {@link SecurityConfig} refuses every unauthenticated request — so the
 * preflight is answered 401 and the real request never happens. A frontend that works from Node (as
 * the golden-path judge does) therefore still fails on its first hop from a browser, which is the
 * only place it is meant to run.
 *
 * <p>Origins are configuration, not a constant: {@code keelbase.cors.allowed-origins} takes a
 * comma-separated list and defaults to {@code *}. The wildcard is defensible here and would not be
 * in a cookie-based API — this runtime is stateless and the caller proves identity with a bearer
 * token, so a request from an unexpected origin carries no ambient credential the browser would
 * attach by itself. Deployments that want a closed list set the property.
 *
 * <p>Preflight itself needs no exclusion from authentication: Spring Security's CORS support
 * answers and short-circuits the preflight before the authorization rules are consulted, but only
 * when the filter chain is told to use this bean — see
 * {@link SecurityConfig#governedEndpoints}.
 */
@Configuration
public class CorsConfig {

    private static final List<String> METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    /** Preflight result may be cached for an hour; it does not carry data and does not vary. */
    private static final long MAX_AGE_SECONDS = 3600L;

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${keelbase.cors.allowed-origins:*}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        if (origins.isEmpty()) {
            throw new IllegalStateException(
                    "keelbase.cors.allowed-origins is set but empty — name at least one origin, or '*'");
        }
        // Patterns rather than literal origins: the same call accepts both, so a deployment that
        // later needs credentials (which rules out a wildcard) changes the property, not this code.
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(METHODS);
        // Headers are not enumerated deliberately. The frontend sends Authorization for identity and
        // Accept-Language for NC-2 message negotiation; listing them here would mean every future
        // header has to be remembered in a second place before it works.
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(MAX_AGE_SECONDS);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
