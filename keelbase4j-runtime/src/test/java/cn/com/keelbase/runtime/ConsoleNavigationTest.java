// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.protocol.WireSchemas;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.IntentPlan;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolResult;
import cn.com.keelbase.runtime.web.ChatTurnService;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

/**
 * A turn whose tool named a place in the console, reported on both channels.
 *
 * <p>Navigation is a tool like any other and is governed like any other, so this is not a second
 * pipeline: a read — no result type, so no side effect and no confirmation — whose result carries a
 * route. What it has of its own is how that route reaches the caller: a {@code navigateTo} field on
 * the frozen {@code chat-response}, and a {@code navigate} event on the stream. The contract declares
 * both, the reference emits both, and this runtime carried neither before this change — which is what
 * the two tests below pin.
 *
 * <p>The rule planner cannot route to a tool it was not written for, so what is declared here is the
 * pair a host would declare: the tool, and a planner that proposes it. Nothing else moves, which is
 * the point — there is no second implementation of the turn anywhere in this diff.
 *
 * <p>一个工具点了控制台某一页的回合，在两个通道上各自被报出的样子。
 *
 * <p>导航与别的工具一样、也被一样地治理，所以这里**不是第二条流水线**：一个读 —— 没有结果类型，故没有副作用、
 * 也不需要确认 —— 其结果里带着一条路径。它自己的东西只有**那条路径怎么到达调用方**：冻结的
 * {@code chat-response} 上的一个 {@code navigateTo} 字段，与流上的一个 {@code navigate} 事件。契约**声明了**
 * 两者、参照实现**发了**两者，而本运行时在这次改动之前**两个都没有** —— 下面两个测试钉的正是这件事。
 *
 * <p>规则规划器路由不了一条它没为之写过的工具，所以这里声明的是宿主会声明的那一对：工具，与一条提议它的规划器。
 * 别处什么都不动，而这正是要点 —— 这次改动里**没有任何第二条「回合」的实现**。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConsoleNavigationTest {

    /**
     * A deployment that knows where its console's pages are.
     *
     * <p>The tool declares no result type, which is the whole of what makes it a read: it takes no
     * write claim, records no side effect and asks for no confirmation, and what it returns is a route
     * the console can act on rather than anything this deployment wrote.
     *
     * 一个**知道自己的控制台有哪些页面**的部署。
     *
     * <p>该工具不声明结果类型，而「是读」的全部就在这一点上：它不占写声明、不记副作用、不要确认，交回的是
     * 控制台**可以据以动作**的一条路径，而不是这个部署写下的任何东西。
     */
    @TestConfiguration
    static class AConsoleThatKnowsItsPages {

        @Bean
        AiTool navigateConsolePage() {
            return new AiTool() {
                @Override
                public String name() {
                    return "navigate_console_page";
                }

                @Override
                public String description() {
                    return "Open a page of this console";
                }

                @Override
                public String riskLevel() {
                    return "R1";
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    return ToolResult.ok(Map.of(ChatTurnService.NAVIGATE_TO, "/users",
                            "pageName", "users"));
                }
            };
        }

        @Bean
        ToolCallPlanner navigatingPlanner() {
            return (message, context) -> Optional.of(new IntentPlan("navigate_console_page", Map.of()));
        }
    }

    @LocalServerPort
    int port;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    /**
     * The non-streaming half. {@code navigateTo} is a field the frozen object declares, so answering
     * with it is not an addition to the shape — it is the part of the shape this runtime was leaving
     * empty, and a caller written against the reference already reads it.
     *
     * 非流式那一半。{@code navigateTo} 是冻结对象**自己声明**的字段，故答它**不是往形状上加东西** —— 它是这个
     * 形状里本运行时**一直空着**的那一块，而照着参照实现写的调用方**本来就在读它**。
     */
    @Test
    void theRouteReachesTheNonStreamingBody() throws Exception {
        Object answer = Envelopes.data(chat("bob", "打开用户管理"));

        WireSchemas.assertConformsTo(answer, "chat-response");
        assertEquals("/users", asMap(answer).get(ChatTurnService.NAVIGATE_TO),
                "a turn that named a route says so on the frozen object: " + answer);
    }

    /**
     * The streaming half. The route travels as its own event rather than inside the reply's prose: the
     * reply says "opened" in whatever words the model chose, and the console cannot navigate on prose.
     * Its place in the order is after {@code text}, so the turn's own rendering has begun by the time
     * the console reads it.
     *
     * 流式那一半。路径作为**它自己的事件**走，而不是塞在 reply 的散文里：reply 用模型挑的词说「已打开」，
     * 而控制台**没法照着散文导航**。它在次序里的位置是 {@code text} **之后**，好让控制台读到它时，本回合
     * 自己的渲染**已经开始**。
     */
    @Test
    void theRouteReachesTheStreamAsItsOwnEvent() throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();
        try (BufferedReader reader = openStream("bob", "打开用户管理")) {
            String line;
            while ((line = reader.readLine()) != null) {
                Map<String, Object> event = readEvent(line);
                if (event != null) {
                    events.add(event);
                }
            }
        }

        assertEquals(List.of("tool_start", "text", "navigate", "tool_end", "done"), types(events),
                "the console expects these in this order; got " + events);
        // Every event is now held to the frozen set, not only the one this test is about. The check was
        // drawn around `navigate` for a reason that has since been repaired: `tool_start` was leaving
        // out the `summary` its payload requires, so the whole stream could not pass it (JV-61). What
        // remains outside this check is `confirmation_request`'s `mode`, which the frozen enum does not
        // contain — and no turn here produces that event, because a navigation tool is a read and a read
        // raises no confirmation. A write turn is ChatStreamTest's to cover.
        //
        // 现在**每个**事件都拿冻结集来量，不只是本测试关心的那一个。它此前只画在 `navigate` 周围，理由是
        // **后来被修掉**的那个：`tool_start` 漏了它载荷所要求的 `summary`，故整条流过不了（`JV-61`）。
        // 仍在这个检查之外的只剩 `confirmation_request` 的 `mode`（冻结枚举里没有它）—— 而本测试的任何回合都
        // 产不出那个事件：导航工具是**读**，而读不起确认。写回合归 `ChatStreamTest` 覆盖。
        events.forEach(event -> WireSchemas.assertConformsTo(event, "sse-event"));
        assertEquals("/users", payload(events, "navigate").get("route"),
                "and the event carries the route itself: " + events);
    }

    private Map<?, ?> chat(String user, String message) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request(user, "/ai/chat", message).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "the call must succeed: " + response.body());
        return asMap(Json.parse(response.body()));
    }

    private BufferedReader openStream(String user, String message) throws Exception {
        HttpResponse<InputStream> response = HttpClient.newHttpClient().send(
                request(user, "/ai/chat/stream", message).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode(), "the stream must open");
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder request(String user, String endpoint, String message) {
        return HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1" + endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestTokens.forUser(user, delegationSecret))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"message\":\"" + message + "\"}", StandardCharsets.UTF_8));
    }

    /** One SSE line, if it carries an event — the same reading the console does. */
    private static Map<String, Object> readEvent(String line) {
        if (!line.startsWith("data:")) {
            return null;
        }
        return asMap(Json.parse(line.substring(5).trim()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static List<String> types(List<Map<String, Object>> events) {
        return events.stream().map(event -> String.valueOf(event.get("type"))).toList();
    }

    private static Map<String, Object> payload(List<Map<String, Object>> events, String type) {
        return events.stream()
                .filter(event -> type.equals(event.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + type + "' event in " + events));
    }
}
