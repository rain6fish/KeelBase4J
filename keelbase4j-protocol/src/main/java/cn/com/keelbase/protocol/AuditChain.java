// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AI Governance Protocol §2 — audit hash chain.
 *
 * <p>{@code hash = HMAC-SHA256(key, (prevHash ?? "genesis") + "|" + canonicalJSON(payload))}.
 * The {@code "genesis"} literal is used for the first record (DB stores NULL) and is deliberately
 * distinct from the empty string. The key is the ASCII/UTF-8 bytes of the configured key string.
 */
public final class AuditChain {

    /** Key derivation for the legacy (pre-independent-key) chain: {@code HMAC-SHA256("keelbase:audit-chain:v1", secret)}. */
    public static final String LEGACY_DOMAIN = "keelbase:audit-chain:v1";

    private AuditChain() {
    }

    /** Compute one record's hash (hex, lowercase). */
    public static String hash(String key, String prevHash, Object payload) {
        String canonical = CanonicalJson.canonical(payload);
        return hmacHex(key, (prevHash == null ? "genesis" : prevHash) + "|" + canonical);
    }

    /** Derive the legacy chain key from a secret (kept for historical records). */
    public static String legacyKey(String secret) {
        return hmacHex(LEGACY_DOMAIN, secret);
    }

    /** A row to verify: id + stored prevHash/hash; the payload is supplied separately. */
    public record ChainRow(int id, String prevHash, String hash) {
    }

    /** Verification outcome; {@code brokenIndex} is 1-based and set only when invalid. */
    public record Verification(boolean valid, int checked, Integer brokenIndex) {
    }

    /**
     * Walk rows in ascending order, recomputing each hash and checking {@code prevHash} continuity.
     * Any candidate key may validate a row (key-rotation support).
     */
    public static Verification verify(
            List<ChainRow> rows, List<String> candidateKeys, Function<ChainRow, Object> payloadFor) {
        String prev = null;
        for (int i = 0; i < rows.size(); i++) {
            ChainRow row = rows.get(i);
            if (!Objects.equals(row.prevHash(), prev)) {
                return new Verification(false, i, i + 1);
            }
            boolean matched = false;
            for (String key : candidateKeys) {
                if (hash(key, prev, payloadFor.apply(row)).equals(row.hash())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return new Verification(false, i, i + 1);
            }
            prev = row.hash();
        }
        return new Verification(true, rows.size(), null);
    }

    static String hmacHex(String key, String message) {
        return toHex(hmac(key.getBytes(StandardCharsets.UTF_8), message.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
