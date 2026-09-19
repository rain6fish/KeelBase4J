// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.demo;

import cn.com.keelbase.protocol.DelegationToken;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mints a delegation token for the demo, so a shell script can call the runtime the way a real
 * caller must.
 *
 * <p>Development entry point, not part of anything shipped: a deployment mints tokens wherever it
 * mints identity (its IdP, its gateway), and the runtime only ever verifies them. This exists
 * because there is no way to authenticate other than presenting a token — which is the property
 * JV-9 established, and the reason a demo needs a signer at all.
 */
public final class DevToken {

    private static final String DEFAULT_AUDIENCE = "keelbase4j";

    private DevToken() {
    }

    /**
     * {@code args}: {@code <userId> <secret> [audience] [ttlSeconds]} — prints the token, nothing else.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: DevToken <userId> <secret> [audience] [ttlSeconds]");
            System.exit(2);
        }
        String audience = args.length > 2 ? args[2] : DEFAULT_AUDIENCE;
        long ttl = args.length > 3 ? Long.parseLong(args[3]) : 3600;

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "local:" + args[0]);
        claims.put("aud", audience);
        claims.put("iss", "keelbase");
        long now = Instant.now().getEpochSecond();
        System.out.println(DelegationToken.sign(claims, now, now + ttl, args[1]));
    }
}
