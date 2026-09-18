// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import cn.com.keelbase.protocol.DelegationToken;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mints the delegation tokens the runtime authenticates with.
 *
 * <p>Tests call the API the way a real caller must: present a token and let the runtime decide what
 * that subject means locally. There is no helper for "call as a user" other than signing one, because
 * signing one is the only way in — which is the point of authenticating at the entry.
 */
final class TestTokens {

    /** Must match {@code keelbase.delegation.audience}. */
    static final String AUDIENCE = "keelbase4j";

    private TestTokens() {
    }

    /** A token for a local subject: {@code alice} → {@code local:alice}. */
    static String forUser(String userId, String secret) {
        return forSubject("local:" + userId, null, secret, AUDIENCE, 3600);
    }

    static String forSubject(String subject, String oidcSubject, String secret) {
        return forSubject(subject, oidcSubject, secret, AUDIENCE, 3600);
    }

    /**
     * Sign a token. {@code ttlSeconds} may be negative, which mints one that has already expired.
     */
    static String forSubject(String subject, String oidcSubject, String secret, String audience,
                             long ttlSeconds) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        if (oidcSubject != null) {
            claims.put("oidcSub", oidcSubject);
        }
        claims.put("aud", audience);
        claims.put("iss", "keelbase");
        long now = Instant.now().getEpochSecond();
        return DelegationToken.sign(claims, now, now + ttlSeconds, secret);
    }
}
