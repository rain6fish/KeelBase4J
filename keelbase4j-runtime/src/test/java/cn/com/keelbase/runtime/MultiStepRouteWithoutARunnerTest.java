// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code POST /ai/task} in a deployment that has no runner: the capability is absent, and the route
 * says so rather than pretending a run happened.
 *
 * <p>This shape is the one a plain runtime deployment is in — no orchestration adapter on the classpath
 * — so it is the shape most callers will meet first. Three readings are being ruled out: a 5xx, which
 * reaches a caller as "服务器内部错误" and tells them nothing about what is missing; a body that looks
 * like a run with no steps, which claims something happened; and a 401/403, which would report a
 * deployment difference as the caller's fault.
 *
 * <p>{@code POST /ai/task} 在一个**没有 runner** 的部署里：这个能力不在，而这条路由**如实说出来**，而不是假装
 * 跑过一次。
 *
 * <p>这一种形状正是「普通的运行时部署」所在的形状 —— classpath 上没有编排适配器 —— 所以它也是多数调用方**最先**
 * 遇到的形状。它排除三种读法：**5xx**（到调用方手上是「服务器内部错误」，说不出缺了什么）· **一个看起来像「跑了
 * 一次、但一步都没有」的正文**（那是在**声称**发生过什么）· **401/403**（那会把一个**部署差异**报成**调用方的错**）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MultiStepRouteWithoutARunnerTest {

    @LocalServerPort
    int port;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void theAnswerSaysTheCapabilityIsAbsentRatherThanInventingARun() throws Exception {
        HttpResponse<String> response = post("{\"message\":\"查一下再记一笔\"}");

        assertEquals(200, response.statusCode(),
                "a deployment difference is answered, not refused: " + response.body());

        Map<String, Object> data = payload(response);
        assertEquals(false, data.get("available"), "the capability is not here: " + data);
        assertTrue(String.valueOf(data.get("reason")).contains("no multi-step task implementation"),
                "and it names what is missing: " + data);
        assertTrue(String.valueOf(data.get("nextStep")).contains("keelbase4j-springai"),
                "with what a deployment does about it: " + data);
        assertNull(data.get("answer"), "no answer was produced, and it does not pretend otherwise");
        assertNull(data.get("pendingToken"));
        assertEquals(List.of(), data.get("steps"), "and no steps were taken: " + data);
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/ai/task"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestTokens.forUser("alice", delegationSecret))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(HttpResponse<String> response) {
        return (Map<String, Object>) ((Map<String, Object>) Json.parse(response.body())).get("data");
    }
}
