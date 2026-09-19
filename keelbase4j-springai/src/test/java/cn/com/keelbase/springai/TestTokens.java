// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import cn.com.keelbase.protocol.DelegationToken;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mints the delegation tokens the runtime authenticates with.
 *
 * <p>Same shape as the runtime's own test helper: a caller proves a subject and nothing else, and
 * the runtime decides what that subject means locally.
 */
final class TestTokens {

    /** Must match {@code keelbase.delegation.audience}. */
    static final String AUDIENCE = "keelbase4j";

    private TestTokens() {
    }

    static String forUser(String userId, String secret) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "local:" + userId);
        claims.put("aud", AUDIENCE);
        claims.put("iss", "keelbase");
        long now = Instant.now().getEpochSecond();
        return DelegationToken.sign(claims, now, now + 3600, secret);
    }
}
