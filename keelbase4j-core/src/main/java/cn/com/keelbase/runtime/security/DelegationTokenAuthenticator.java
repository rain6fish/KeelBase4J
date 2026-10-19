// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import cn.com.keelbase.protocol.DelegationToken;
import cn.com.keelbase.runtime.identity.AuthenticatedSubject;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * A local sign-then-verify probe, for the integration-health surface.
     *
     * <p>What it proves is narrow and it is stated as such: the configured secret is not blank, the
     * configured audience is not blank, and the frozen signer and verifier agree on a token minted
     * here. What it does <b>not</b> prove is that the upstream issuer holds the same secret — no
     * local probe can, because the evidence for that is an inbound token the issuer signed, which the
     * caller of the health surface already presented. The health report keeps those two readings
     * separate ({@code selfTest} and {@code inboundVerified}) rather than letting one stand in for
     * the other.
     *
     * <p>The secret is never returned. Only the boolean outcome, the non-secret audience, and the
     * verifier's reason on failure leave this method.
     *
     * 本地「先签发、再验签」探针，供接入健康度面使用。
     *
     * <p>它证明的东西很窄，也照窄的说法写出来：配置的 secret 非空、配置的 audience 非空、冻结的签发器与
     * 验签器对一枚本地铸造的令牌意见一致。它**不**证明上游签发方持有同一个 secret —— 任何本地探针都证明
     * 不了，因为那件事的证据是上游签过、且调用方已经出示过的一枚入站令牌。健康报告把这两个读数**分开**
     * （`selfTest` 与 `inboundVerified`），不让一个冒充另一个。
     *
     * <p>secret 绝不返回。离开本方法的只有布尔结论、非密的 audience，以及失败时验签器给的理由。
     */
    public Probe probe() {
        boolean secretConfigured = secret != null && !secret.isBlank();
        boolean audienceConfigured = audience != null && !audience.isBlank();
        if (!secretConfigured || !audienceConfigured) {
            String reason = !secretConfigured
                    ? "keelbase.delegation.secret is blank"
                    : "keelbase.delegation.audience is blank";
            return new Probe(secretConfigured, audienceConfigured, false, audience, reason);
        }
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "keelbase-status-probe");
        claims.put("aud", audience);
        claims.put("iss", "keelbase");
        String token = DelegationToken.sign(claims, now, now + 60, secret);
        DelegationToken.Result verified = DelegationToken.verify(token, secret, audience, now);
        return new Probe(true, true, verified.ok(), audience, verified.ok() ? null : verified.reason());
    }

    /**
     * The probe's answer. {@code roundTrip} is the sign-then-verify outcome; {@code reason} is set only
     * when the round trip failed or a required setting is blank.
     *
     * 探针的答案。`roundTrip` 是签发后再验签的结果；`reason` 只在往返失败或某项必填设置为空时才有值。
     */
    public record Probe(boolean secretConfigured, boolean audienceConfigured, boolean roundTrip,
                        String audience, String reason) {
    }
}
