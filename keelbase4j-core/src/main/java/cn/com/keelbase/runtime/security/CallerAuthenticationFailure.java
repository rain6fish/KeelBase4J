// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Why a request proved nothing, in the three pieces a caller can act on: what happened, why, and what
 * to do next.
 *
 * <p>The authenticator already knows which check refused the token — the verifier names it in
 * {@link cn.com.keelbase.protocol.DelegationToken.Result#code()}. This record carries that answer to
 * the entry point instead of reducing every refusal to the same {@code 401}. The values behind the
 * verdict ({@code expectedAudience} / {@code actualAudience}, the issuer, the expiry) travel in
 * {@code detail} so the diagnosis can quote them.
 *
 * <p>It deliberately has no field for the secret. A refusal may say that the signature did not
 * match; it must never say what the deployment's secret is, and it does not echo the token.
 *
 * <p>The {@code code} values are stable enough to be matched by a client; the prose is for a human
 * and is written in Chinese, which is the language this runtime's actionable copy uses.
 *
 * <p>一条请求为什么什么都没证明，拆成调用方能行动的三块：**发生了什么、为什么、下一步做什么**。
 *
 * <p>认证器本来就知道是哪道检查拒了令牌 —— 验签器在
 * {@link cn.com.keelbase.protocol.DelegationToken.Result#code()} 里点过名。本记录把那个答案带到入口，
 * 而不是把每一次拒绝都压成同一个 `401`。结论背后的读数（`expectedAudience` / `actualAudience`、签发方、
 * 过期时刻）放在 `detail` 里，好让诊断引用具体值。
 *
 * <p>它**刻意没有** secret 字段。一次拒绝可以说「签名不符」；它**绝不能**说出部署的 secret 是什么，
 * 也不回显令牌。
 *
 * <p>`code` 值稳定到可以被客户端匹配；文案给人读、用中文写 —— 这正是本运行时可执行文案使用的语言。
 */
public record CallerAuthenticationFailure(String code, String what, String why, String nextStep,
                                          Map<String, Object> detail) {

    /** No Authorization header, or one that is not a Bearer token. */
    public static final String CODE_MISSING_TOKEN = "AUTH_MISSING_TOKEN";

    /** The token verified, but this deployment has no local identity for its subject. */
    public static final String CODE_UNKNOWN_SUBJECT = "AUTH_UNKNOWN_SUBJECT";

    /** A deployment-supplied authenticator refused without naming a check. */
    public static final String CODE_UNVERIFIED = "AUTH_UNVERIFIED";

    public CallerAuthenticationFailure {
        // LinkedHashMap, not Map.copyOf: a missing claim is reported as null, and Map.copyOf refuses
        // null values. The map is still made unmodifiable so the record stays immutable.
        //
        // 用 LinkedHashMap 而不是 Map.copyOf：缺失的声明按 null 报，而 Map.copyOf 拒绝 null 值。
        // 仍包成不可修改，记录保持不可变。
        detail = detail == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    /** The token was absent or not presented as a Bearer token. */
    public static CallerAuthenticationFailure missingToken() {
        return new CallerAuthenticationFailure(CODE_MISSING_TOKEN,
                "请求未携带委托令牌",
                "`Authorization` 头缺失，或不是 `Bearer <token>` 形式",
                "在请求头加入 `Authorization: Bearer <token>`；令牌由 KeelBase 用 "
                        + "`keelbase.delegation.secret` 签发",
                Map.of());
    }

    /**
     * The deployment supplied its own {@link CallerAuthenticator}, which refused without naming a
     * check. The entry point cannot invent a narrower reason than the one it was given.
     *
     * 部署自带了 {@link CallerAuthenticator}，而它拒绝时没有点明是哪道检查。入口造不出比它拿到的更窄的
     * 理由。
     */
    public static CallerAuthenticationFailure unverified() {
        return new CallerAuthenticationFailure(CODE_UNVERIFIED,
                "认证失败",
                "本部署的 `CallerAuthenticator` 没有认可这次请求",
                "检查请求凭据；该部署自行认证调用方，具体凭据格式由部署方决定",
                Map.of());
    }

    /**
     * The token verified, but its subject is not in this deployment's local directory.
     *
     * <p>The subject is echoed because it is the caller's own claim and it is the one value they need
     * to have registered. Nothing else from the token is included.
     *
     * 令牌验过了，但它的主体不在本部署的本地身份目录里。
     *
     * <p>回显主体，因为那是调用方自己的声明，也是他们需要去登记的那个值。令牌里的其它东西一律不带。
     */
    public static CallerAuthenticationFailure unknownSubject(String subject) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("subject", subject);
        return new CallerAuthenticationFailure(CODE_UNKNOWN_SUBJECT,
                "令牌有效，但本部署不认识该主体",
                "令牌 `sub` 是 `" + subject + "`，本地身份目录中没有对应条目",
                "让管理员在本地身份目录（`LocalIdentities` 或宿主的 `IdentityResolver`）登记该 `sub`，"
                        + "或改用已登记的账号签发令牌",
                detail);
    }
}
