// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI Governance Protocol §3 — delegation token (JWT HS256).
 *
 * <p>{@code header.payload.signature}, {@code signature = HMAC-SHA256(secret, header + "." + payload)}
 * base64url-encoded. Claims: {@code sub} (unified identity mapping key, {@code local:<id>} or an OIDC
 * subject), {@code oidcSub}, {@code aud} (target-system audience — cross-system reuse is rejected),
 * {@code iss = "keelbase"}, {@code iat}/{@code exp}. Delegation never escalates privilege: the mapped
 * local user's own permissions apply after mapping.
 */
public final class DelegationToken {

    private static final String ISSUER = "keelbase";
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private DelegationToken() {
    }

    /** Sign a token. {@code iat}/{@code exp} are absolute epoch seconds. */
    public static String sign(Map<String, Object> claims, long iat, long exp, String secret) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", claims.get("sub"));
        if (claims.containsKey("oidcSub")) {
            payload.put("oidcSub", claims.get("oidcSub"));
        }
        payload.put("aud", claims.get("aud"));
        payload.put("iss", claims.get("iss"));
        payload.put("iat", (double) iat);
        payload.put("exp", (double) exp);

        String h = b64Url(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        String p = b64Url(CanonicalJson.json(payload).getBytes(StandardCharsets.UTF_8));
        String sig = b64Url(AuditChain.hmac(secret.getBytes(StandardCharsets.UTF_8),
                (h + "." + p).getBytes(StandardCharsets.UTF_8)));
        return h + "." + p + "." + sig;
    }

    /**
     * Machine-readable failure codes. They name <em>which check</em> refused a token, so a caller can
     * turn a refusal into a specific instruction without matching on prose. They are not part of the
     * frozen wire contract: the contract fixes the token, not this Java result object.
     *
     * 机器可读的失败码。它们点名**是哪一道检查**拒绝了令牌，故调用方不必靠匹配文字就能把一次拒绝翻成
     * 具体的处置指引。它们不属于冻结的 wire 契约：契约钉住的是令牌，不是这个 Java 结果对象。
     */
    public static final String CODE_MALFORMED = "malformed_token";
    public static final String CODE_SIGNATURE_MISMATCH = "signature_mismatch";
    public static final String CODE_PAYLOAD_UNPARSEABLE = "unparseable_payload";
    public static final String CODE_PAYLOAD_NOT_OBJECT = "non_object_payload";
    public static final String CODE_UNKNOWN_ISSUER = "unknown_issuer";
    public static final String CODE_AUDIENCE_MISMATCH = "audience_mismatch";
    public static final String CODE_EXPIRED = "token_expired";

    /**
     * Result of {@link #verify}. {@code reason} is set only on failure; {@code code} names the failed
     * check, and {@code detail} carries the readings behind it (expected/actual audience, issuer,
     * expiry) so a diagnosis can quote values instead of describing them.
     *
     * <p>The three-argument constructor keeps the published shape source-compatible.
     *
     * {@link #verify} 的结果。`reason` 只在失败时有值；`code` 点名失败的那道检查，`detail` 带着它背后的
     * 读数（期望/实得 audience、签发方、过期时刻），好让诊断引用具体值而不是描述一番。
     *
     * <p>三参构造保留已发布的形状，源码兼容。
     */
    public record Result(boolean ok, Map<String, Object> payload, String reason, String code,
                         Map<String, Object> detail) {

        public Result(boolean ok, Map<String, Object> payload, String reason) {
            this(ok, payload, reason, null, Map.of());
        }
    }

    /**
     * Verify signature + {@code iss} + {@code aud} + {@code exp}.
     *
     * @param expectedAudience required audience, or {@code null} to skip the audience check
     * @param nowSec           current time (epoch seconds), or {@code null} to skip the expiry check
     */
    public static Result verify(String token, String secret, String expectedAudience, Long nowSec) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return fail(CODE_MALFORMED, "JWT is not header.payload.signature", Map.of());
        }
        String header = parts[0];
        String payloadSeg = parts[1];
        String sig = parts[2];

        String expectedSig = b64Url(AuditChain.hmac(secret.getBytes(StandardCharsets.UTF_8),
                (header + "." + payloadSeg).getBytes(StandardCharsets.UTF_8)));
        if (!sig.equals(expectedSig)) {
            return fail(CODE_SIGNATURE_MISMATCH,
                    "signature mismatch (payload tampered or wrong secret)", Map.of());
        }

        Object parsed;
        try {
            parsed = Json.parse(new String(b64UrlDecode(payloadSeg), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return fail(CODE_PAYLOAD_UNPARSEABLE, "payload is not parseable JSON", Map.of());
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            return fail(CODE_PAYLOAD_NOT_OBJECT, "payload is not a JSON object", Map.of());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        map.forEach((k, v) -> payload.put(String.valueOf(k), v));

        if (!ISSUER.equals(payload.get("iss"))) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("issuer", payload.get("iss"));
            return fail(CODE_UNKNOWN_ISSUER, "iss is not keelbase: " + payload.get("iss"), detail);
        }
        if (expectedAudience != null && !expectedAudience.equals(payload.get("aud"))) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("expectedAudience", expectedAudience);
            detail.put("actualAudience", payload.get("aud"));
            return fail(CODE_AUDIENCE_MISMATCH,
                    "aud mismatch: expected " + expectedAudience + ", got " + payload.get("aud"),
                    detail);
        }
        if (nowSec != null && payload.get("exp") instanceof Number n && n.longValue() < nowSec) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("expiredAt", n.longValue());
            detail.put("now", nowSec);
            return fail(CODE_EXPIRED, "token expired", detail);
        }
        return new Result(true, payload, null, null, Map.of());
    }

    static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] b64UrlDecode(String s) {
        int pad = (4 - s.length() % 4) % 4;
        return Base64.getUrlDecoder().decode(s + "=".repeat(pad));
    }

    private static Result fail(String code, String reason, Map<String, Object> detail) {
        return new Result(false, null, reason, code, detail);
    }
}
