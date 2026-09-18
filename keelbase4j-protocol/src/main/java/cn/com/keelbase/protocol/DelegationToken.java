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

    /** Result of {@link #verify}. {@code reason} is set only on failure. */
    public record Result(boolean ok, Map<String, Object> payload, String reason) {
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
            return fail("JWT is not header.payload.signature");
        }
        String header = parts[0];
        String payloadSeg = parts[1];
        String sig = parts[2];

        String expectedSig = b64Url(AuditChain.hmac(secret.getBytes(StandardCharsets.UTF_8),
                (header + "." + payloadSeg).getBytes(StandardCharsets.UTF_8)));
        if (!sig.equals(expectedSig)) {
            return fail("signature mismatch (payload tampered or wrong secret)");
        }

        Object parsed;
        try {
            parsed = Json.parse(new String(b64UrlDecode(payloadSeg), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return fail("payload is not parseable JSON");
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            return fail("payload is not a JSON object");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        map.forEach((k, v) -> payload.put(String.valueOf(k), v));

        if (!ISSUER.equals(payload.get("iss"))) {
            return fail("iss is not keelbase: " + payload.get("iss"));
        }
        if (expectedAudience != null && !expectedAudience.equals(payload.get("aud"))) {
            return fail("aud mismatch: expected " + expectedAudience + ", got " + payload.get("aud"));
        }
        if (nowSec != null && payload.get("exp") instanceof Number n && n.longValue() < nowSec) {
            return fail("token expired");
        }
        return new Result(true, payload, null);
    }

    static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] b64UrlDecode(String s) {
        int pad = (4 - s.length() % 4) % 4;
        return Base64.getUrlDecoder().decode(s + "=".repeat(pad));
    }

    private static Result fail(String reason) {
        return new Result(false, null, reason);
    }
}
