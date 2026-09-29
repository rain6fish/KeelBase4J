// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import cn.com.keelbase.runtime.identity.AuthenticatedSubject;
import jakarta.servlet.http.HttpServletRequest;

/**
 * How a request's caller is established — the seam an embedded deployment replaces (ADR-0017 D6, JV-35).
 *
 * <p>Standalone, the caller proves who they are with a delegation token, and that is the default here.
 * Embedded in a host, the caller has already been authenticated by the host's own machinery: the host
 * knows its session, its token, its user table. Making the host re-present a KeelBase delegation token
 * would mean two logins for one person and two identities in one request, which is the arrangement the
 * embedding exists to avoid. So the deployment answers this question instead, and everything downstream
 * is unchanged: this returns the same {@link AuthenticatedSubject} the token path produced, the filter
 * writes it into the security context the same way, and {@code CurrentPrincipal} and the whole
 * authorization chain never learn which deployment they are in.
 *
 * <p>One implementation must be a Spring bean. The core registers the delegation-token one on
 * {@code @ConditionalOnMissingBean}, so a deployment that declares its own replaces it rather than
 * colliding with it.
 *
 * <p>What this is <em>not</em> is a place to decide what the caller may do. It answers "who is this
 * request" and stops there, exactly as the delegation filter did (ADR-0004 D3) — the permission
 * question is answered by the frozen authorization contracts, downstream of {@code CurrentPrincipal}.
 *
 * <p>**一条请求的调用方是怎么确立的** —— 这就是嵌入的部署要替换的那道缝（ADR-0017 D6，JV-35）。
 *
 * <p>独立部署时，调用方用委托令牌证明自己是谁，那也是这里的默认。嵌进宿主时，调用方**已经**被宿主自己的
 * 机制认证过了：宿主知道自己的会话、自己的令牌、自己的用户表。让宿主的人再出示一次 KeelBase 委托令牌，等于
 * 一个人登录两次、一个请求里存在两个身份——而嵌入正是为了避开这种安排。于是这个问题改由部署方回答，下游一切
 * 不变：它返回的还是令牌那条路产出的同一个 `AuthenticatedSubject`，过滤器还是用同样的方式把它写进 security
 * context，而 `CurrentPrincipal` 与整条授权链**从不**知道自己在哪个部署里。
 *
 * <p>实现必须是一个 Spring bean。核心把委托令牌那一个注册在 `@ConditionalOnMissingBean` 上，所以声明了自己
 * 实现的部署是**取代**它，而不是与它相撞。
 *
 * <p>这里**不是**决定调用方能做什么的地方。它只回答「这条请求是谁」，到此为止，与当年那个委托令牌过滤器完全
 * 一样（ADR-0004 D3）——权限问题由冻结的授权契约在下游回答，在 `CurrentPrincipal` 之后。
 */
public interface CallerAuthenticator {

    /**
     * The caller this request proves itself to be, or {@code null} when it proves nothing — in which
     * case the request is left unauthenticated and the chain answers 401. An implementation that
     * cannot verify what it was given returns {@code null} rather than a subject it did not verify.
     *
     * <p>这条请求所证明的调用方；它什么都没证明时返回 `null`——此时该请求保持未认证、由链答 401。实现若
     * 无法验证拿到的东西，就返回 `null`，而不是返回一个它并未验证过的主体。
     */
    AuthenticatedSubject authenticate(HttpServletRequest request);
}
