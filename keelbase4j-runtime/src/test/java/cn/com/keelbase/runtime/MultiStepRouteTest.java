// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.pipeline.TaskRun;
import cn.com.keelbase.runtime.pipeline.TaskRunner;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code POST /ai/task} — where a caller asks for a message to be carried out as a task, and the route
 * reports what the run did.
 *
 * <p>The runner here is a stand-in: it answers without a model, so this test is about the route and not
 * about orchestration. What it pins is the part that belongs to the runtime — the run's report reaches
 * the caller in order and unreshaped, the caller the run is governed as is the one who asked, and a
 * message that is not a message is refused rather than run.
 *
 * <p>The other deployment shape — no runner on the classpath at all — is
 * {@link MultiStepRouteWithoutARunnerTest}: it is a different answer, so it is a different test.
 *
 * <p>{@code POST /ai/task} —— 调用方在这里要求把一条消息当作任务办完，而这条路由汇报这次运行做了什么。
 *
 * <p>这里的 runner 是个**替身**：不带模型也能作答，所以本测试考的是**路由**、不是编排。它钉住的是属于运行时的
 * 那部分 —— 运行报告**按序、不被重塑**地到达调用方；这次运行所依据的调用者就是提问的那个人；而一条不是消息的
 * 消息会被**拒掉**，不是拿去跑。
 *
 * <p>另一种部署形状（classpath 上**没有** runner）在 {@link MultiStepRouteWithoutARunnerTest}：那是**另一个
 * 答案**，所以是另一个测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MultiStepRouteTest {

    @LocalServerPort
    int port;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    /**
     * A runner that answers without a model and says who it ran for — so "the caller reached the run"
     * is readable from the response rather than inferred.
     */
    @TestConfiguration
    static class AStandInRunner {

        @Bean
        TaskRunner taskRunner() {
            return (message, principal) -> new TaskRun(
                    "answered " + message + " for " + principal.userId(),
                    List.of(new TaskRun.Step("analyze_customer_risk",
                                    ExecutionOutcome.executed(Map.of("level", "high"), null)),
                            new TaskRun.Step("create_followup",
                                    new ExecutionOutcome("pending_confirmation", null, "token-1", null, null))),
                    "token-1");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRouteReportsWhatTheRunDidInOrder() throws Exception {
        Map<String, Object> data = data(post("/api/v1/ai/task", "alice", "{\"message\":\"查一下再记一笔\"}"));

        assertEquals(true, data.get("available"), "a runner is on the classpath here");
        assertEquals("answered 查一下再记一笔 for alice", data.get("answer"),
                "the answer is the run's, and the caller reached it: " + data);
        assertEquals("token-1", data.get("pendingToken"),
                "the step waiting on a person is carried back, not decided here");

        List<Map<String, Object>> steps = (List<Map<String, Object>>) data.get("steps");
        assertEquals(2, steps.size(), "both steps are reported: " + steps);
        assertEquals("analyze_customer_risk", steps.get(0).get("tool"), "in the order they happened");
        assertEquals("create_followup", steps.get(1).get("tool"));
        Map<String, Object> first = (Map<String, Object>) steps.get(0).get("outcome");
        assertEquals("executed", first.get("status"));
        Map<String, Object> second = (Map<String, Object>) steps.get(1).get("outcome");
        assertEquals("pending_confirmation", second.get("status"));
        assertEquals("token-1", second.get("token"), "and the waiting step carries its handle");
    }

    @Test
    void aMessageThatIsNotAMessageIsRefused() throws Exception {
        HttpResponse<String> response = post("/api/v1/ai/task", "alice", "{\"message\":\"  \"}");

        assertEquals(400, response.statusCode(), "an empty message is not a task: " + response.body());
        assertTrue(response.body().contains("message is required"), "and it says so: " + response.body());
    }

    @Test
    void theCallerIsRequired() throws Exception {
        HttpResponse<String> response = post("/api/v1/ai/task", null, "{\"message\":\"查一下\"}");

        assertEquals(401, response.statusCode(),
                "the same chain as every other route: " + response.body());
    }

    private HttpResponse<String> post(String path, String user, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (user != null) {
            request.header("Authorization", "Bearer " + TestTokens.forUser(user, delegationSecret));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The envelope's payload; the frozen success shape wraps every controller's answer. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), "answered: " + response.body());
        Map<String, Object> envelope = (Map<String, Object>) Json.parse(response.body());
        assertNotNull(envelope.get("data"), "the frozen envelope carries a payload: " + response.body());
        return (Map<String, Object>) envelope.get("data");
    }
}
