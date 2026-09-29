// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import cn.com.keelbase.protocol.DelegationToken;
import cn.com.keelbase.runtime.identity.AuthenticatedSubject;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpHeaders;

/**
 * The default {@link CallerAuthenticator}: the caller proves who they are with a delegation token.
 *
 * <p>Verification is the frozen protocol's own {@link DelegationToken#verify} rather than a second
 * implementation of JWT checks: two verifiers would be two things to keep in step, and the protocol is
 * the one that is frozen. A token that fails verification — bad signature, wrong audience, expired —
 * simply proves nothing, and the reason is not echoed back to the caller.
 *
 * <p>This used to be the body of a filter. It is an object rather than a filter now because the
 * question it answers is the deployment's, while the mechanics of asking it — once per request, writing
 * the answer into the security context, running at the right point in the chain — are this runtime's and
 * should not be re-implemented by whoever embeds it (see {@link CallerAuthenticator}).
 *
 * <p>`CallerAuthenticator` 的**默认实现**：调用方用委托令牌证明自己是谁。
 *
 * <p>验证走的是冻结协议自己的 `DelegationToken#verify`，而不是再写一遍 JWT 校验：两个验证器就是两样要
 * 保持同步的东西，而协议是**被冻结**的那一个。验证不过的令牌——签名不对、受众不对、过期——就是什么都没
 * 证明，理由也不回传给调用方。
 *
 * <p>这段代码原本是一个过滤器的方法体。现在它是对象而不是过滤器，因为**它回答的问题是部署方的**，而「怎么问」
 * ——每请求一次、把答案写进 security context、在链上正确的位置运行——是**本运行时的**，不该由嵌入它的人
 * 再实现一遍（见 `CallerAuthenticator`）。
 */
public class DelegationTokenAuthenticator implements CallerAuthenticator {

    private static final String BEARER = "Bearer ";

    private final String secret;
    private final String audience;

    public DelegationTokenAuthenticator(String secret, String audience) {
        this.secret = secret;
        this.audience = audience;
    }

    @Override
    public AuthenticatedSubject authenticate(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        DelegationToken.Result verified = DelegationToken.verify(
                header.substring(BEARER.length()), secret, audience, Instant.now().getEpochSecond());
        if (!verified.ok()) {
            return null;
        }
        Object oidcSubject = verified.payload().get("oidcSub");
        return new AuthenticatedSubject(
                String.valueOf(verified.payload().get("sub")),
                oidcSubject == null ? null : String.valueOf(oidcSubject));
    }
}
