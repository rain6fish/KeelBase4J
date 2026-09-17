// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Request security: authenticate at the entry, and stop there.
 *
 * <p>Everything this runtime exposes is governed, so every endpoint requires an authenticated
 * caller. Beyond that there is deliberately <b>no authorization in this file</b> — no
 * {@code hasRole}, no {@code @PreAuthorize}, no URL-to-role rules. Whether an identity may do a
 * thing is decided by the frozen authorization contracts, which is the line ADR-0004 draws: Spring
 * Security answers "who is this request", KeelBase answers "may this AI behaviour happen".
 *
 * <p>Putting role rules here would not merely duplicate that decision — it would be a second,
 * silently diverging answer to the same question.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain governedEndpoints(HttpSecurity http,
                                          DelegationTokenAuthenticationFilter tokens) throws Exception {
        http
                // A stateless token API: no session to fix, no browser to forge a request from.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // An error dispatch is the container re-rendering a failure this chain has
                        // already handled — a 403 from a controller, say. Demanding authentication a
                        // second time there would turn every refusal into a 401 and hide the status
                        // that actually explains it. A direct request to the error path is still an
                        // ordinary dispatch, so it is still authenticated.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(tokens, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling.authenticationEntryPoint(
                        (request, response, denied) -> response.sendError(
                                HttpServletResponse.SC_UNAUTHORIZED, "authentication required")));
        return http.build();
    }
}
