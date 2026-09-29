// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

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
 *
 * <p>请求安全：在入口认证，到此为止。
 *
 * <p>本运行时暴露的一切都受治理，故每个端点都要求已认证的调用方。除此之外，本文件**刻意不含任何授权**
 * —— 没有 {@code hasRole}、没有 {@code @PreAuthorize}、没有 URL 到角色的规则。「某身份能否做某事」
 * 由冻结的授权契约裁定，这正是 ADR-0004 划的那条线：Spring Security 回答「这是谁」，KeelBase 回答
 * 「这段 AI 行为允不允许」。
 *
 * <p>把角色规则写在这里不只是重复那个判断 —— 它会是同一个问题的**第二份、且会悄悄分叉的答案**。
 *
 * <p><b>Where this chain stops</b> (JV-35 F1): this core is built to be embedded in a host that
 * arrives with a security chain of its own, and Spring Security has no notion of "the application's
 * own chain" — it matches by path and orders by {@code @Order}. Two chains that each match any
 * request are not even resolved by order: {@code WebSecurityConfiguration} refuses to build them
 * ({@code UnreachableFilterChainException}), so the embedded application does not start. Hence the
 * scope: {@link OwnedRoutes} — the paths this runtime's own controllers serve, derived from the
 * controllers rather than listed — and {@code @Order(1)}, which puts this chain ahead of a host
 * chain that declares no order (the host is entitled to declare none).
 *
 * <p><b>本条链到哪为止</b>（JV-35 F1）：本核心是要被**嵌进自带安全链的宿主**里的，而 Spring Security
 * 没有「应用自己那条链」这个概念 —— 它按路径匹配、按 {@code @Order} 排序。两条都匹配任意请求的链甚至
 * 轮不到排序：{@code WebSecurityConfiguration} 直接拒绝装配（{@code UnreachableFilterChainException}）
 * ⇒ **嵌入后的应用起不来**。故有 {@link OwnedRoutes} 这个范围 —— 它治理的是**本运行时自己的控制器**所
 * 服务的路径，从控制器推导而来、而非手写清单 —— 以及 {@code @Order(1)}：它排在**未声明 order 的宿主链**
 * 之前（宿主不声明 order 是它的正当默认）。
 *
 * <p>The prefix on this class name is load-bearing, not decoration. The class is found by a
 * component scan rooted in a library's package, and a bean name is derived from the simple class
 * name — so a class called {@code SecurityConfig} collides with any host that has one of its own,
 * and the host fails to start before any of the above can matter. Measured, not theorised: the first
 * host run died on exactly that. Do not shorten it.
 *
 * <p>类名上的前缀是**承重的**，不是装饰。本类由**根在库包里的**组件扫描发现，而 bean 名取自简单类名
 * ——所以一个叫 {@code SecurityConfig} 的类会和任何有同名类的宿主相撞，宿主在以上任何一条成为问题
 * 之前就起不来。**实测**而非推想：宿主首次运行正是死在这里。**不要简写它。**
 */
@Configuration
@EnableWebSecurity
public class RuntimeSecurityConfig {

    @Order(1)
    @Bean
    SecurityFilterChain governedEndpoints(HttpSecurity http, CallerAuthenticator callers,
            ObjectProvider<RequestMappingHandlerMapping> mappings) throws Exception {
        http
                // Only the paths this runtime's own controllers serve. Everything else in an embedded
                // process belongs to the host's own chain — which is also the only shape Spring
                // Security accepts when both chains are present (see the class javadoc).
                .securityMatcher(new OwnedRoutes(mappings))
                // A stateless token API: no session to fix, no browser to forge a request from.
                .csrf(AbstractHttpConfigurer::disable)
                // Wires the CorsConfigurationSource bean in. Without this line Spring Security does
                // not know CORS exists, so a browser's preflight falls through to
                // `anyRequest().authenticated()` below and is refused — and that failure shows up
                // only in a browser, never in Node, which is why the golden-path judge never saw it.
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // The F5 self-description endpoints are deliberately unauthenticated. A
                        // frontend has to learn which capabilities this system exposes before anyone
                        // holds a token — it is how it decides what to render at all (ADR-0002 Rev-8).
                        // They disclose no caller and no data: only what this deployment declares
                        // itself to be. The reference marks the same two paths public.
                        //
                        // The login page's two calls are public for the same reason and by the same
                        // ruling: it asks which federated providers exist before anyone can log in,
                        // and reports a page visit before there is a session to attribute it to.
                        // Requiring a token for either would make the very page that obtains one
                        // unreachable, and the reference marks both public too.
                        .requestMatchers("/app/capabilities", "/app/provenance",
                                "/auth/login-stats", "/auth/oauth/providers").permitAll()
                        // An error dispatch is the container re-rendering a failure this chain has
                        // already handled — a 403 from a controller, say. Demanding authentication a
                        // second time there would turn every refusal into a 401 and hide the status
                        // that actually explains it. A direct request to the error path is still an
                        // ordinary dispatch, so it is still authenticated.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // An async dispatch is the container coming back to a request it suspended
                        // earlier — the streaming chat endpoint finishing, or giving up on a
                        // confirmation nobody decided. The delegation filter does not run again on
                        // that pass (a OncePerRequestFilter skips async dispatches), so there is no
                        // context here by construction; reading that as unauthenticated refuses the
                        // tail of a request that was authenticated on the way in, and the response
                        // dies mid-body. Only the container produces this dispatch type, and only for
                        // a request that already cleared this chain — so it opens no way in.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new CallerAuthenticationFilter(callers),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling.authenticationEntryPoint(
                        (request, response, denied) -> response.sendError(
                                HttpServletResponse.SC_UNAUTHORIZED, "authentication required")));
        return http.build();
    }
}
