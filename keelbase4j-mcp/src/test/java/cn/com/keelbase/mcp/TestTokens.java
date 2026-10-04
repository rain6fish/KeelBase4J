// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.mcp;

import cn.com.keelbase.protocol.DelegationToken;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mints the delegation tokens the runtime authenticates with.
 *
 * <p>Same shape as the runtime's own test helper, and the same as the Spring AI adapter's: a caller
 * proves a subject and nothing else, and the runtime decides what that subject means locally. Copied
 * rather than shared because a test helper is not an artifact — the alternative is a test-jar
 * dependency between two adapter modules that have nothing to do with each other.
 *
 * 铸造运行时用来认证的委托令牌。
 *
 * <p>与运行时自己的测试助手同形，也与 Spring AI 适配器的那个同形：调用方只证一个 subject、别的什么都不证，而
 * 运行时自己决定那个 subject 在本地意味着什么。**抄一份而不是共享**，因为测试助手不是 artifact——另一条路是
 * 让两个彼此无关的适配器模块之间产生一条 test-jar 依赖。
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
