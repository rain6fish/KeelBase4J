// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.protocol.Vectors;
import cn.com.keelbase.protocol.WireSchemas;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The two shapes every response is made of, held against the frozen schemas.
 *
 * <p>These are the objects a conformance audit found unchecked: the envelope wraps <em>every</em>
 * successful response ({@code ApiResponseAdvice}) and the failure body covers every refusal, so a
 * drift in either is a drift everywhere — and until this test, nothing pinned either of them to the
 * contract. The rest of this module's assertions are structural, by design and for a reason stated in
 * {@code AppContractTest}; these two are the case where the schema is a single small shape and holding
 * it directly costs one call.
 *
 * <p><b>The check is the shared one</b> ({@link WireSchemas}, reached through this module's existing
 * test-jar dependency), so the meaning of "conforms" is the same here as in the protocol module's wire
 * tests rather than a second opinion that can drift.
 *
 * 每一条响应都由这两个形状构成，这里把它们拿去对冻结 schema。
 *
 * <p>它们正是一次符合性盘点查出来的、无人校验的对象：信封裹着**每一条成功响应**
 * （{@code ApiResponseAdvice}），失败体覆盖**每一次拒绝** —— 任一处漂移就是处处漂移，而在本测试之前，
 * **没有任何东西**把它们钉在契约上。本模块其余的断言是**结构**的，那是刻意的、理由写在
 * {@code AppContractTest} 里；而这两个是「schema 就一小块、直接对它只花一次调用」的那种情形。
 *
 * <p><b>用的是共享的那份检查</b>（{@link WireSchemas}，经本模块既有的 test-jar 依赖够到），所以
 * 「符合」在这里的含义与协议模块的 wire 测试**完全相同**，而不是第二份会各自漂移的意见。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class WireShapeConformanceTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    CustomerRepository customers;

    /** A customer the caller owns — a write cannot run without one, and the lookup happens on approval. */
    private Long aCustomerOf(String owner) {
        return customers.save(new Customer("Conformance Co", "low", owner)).getId();
    }

    @Test
    void aSuccessfulResponseCarriesTheFrozenEnvelope() {
        ResponseEntity<String> response = rest.getForEntity("/app/capabilities", String.class);

        assertEquals(200, response.getStatusCode().value(), "the probe endpoint must answer");
        Map<String, Object> body = Vectors.map(Json.parse(response.getBody()));
        WireSchemas.assertConformsTo(body, "api-response");
    }

    @Test
    void aRefusedRequestCarriesTheFrozenFailureBody() {
        // No token: the point is a refusal, and that a refusal is still contract-shaped. A bare
        // status with an empty or ad-hoc body is exactly what a frontend cannot act on.
        ResponseEntity<String> response = rest.getForEntity("/ai/my/confirmations", String.class);

        assertTrue(response.getStatusCode().is4xxClientError(),
                "the call is refused, not served: " + response.getStatusCode());
        assertTrue(response.getStatusCode().value() != 404,
                "the refusal must be authorisation, not a missing route");
        Map<String, Object> body = Vectors.map(Json.parse(response.getBody()));
        WireSchemas.assertConformsTo(body, "error-body");
    }

    // The three shapes this audit once measured and left out of the suite are all answered now, and each
    // is pinned below rather than described: `/ai/chat`'s answer is the frozen `chat-response` with
    // nothing added, the effect item answers the frozen `traceItem`, and the audit chain answers its own
    // object. The last one to give way was the chat answer — this runtime had argued its superset was a
    // declared mapping, and the contract, the reference and the protocol's prose all disagreed.
    //
    // 这次盘点**量过、当时没写进套件**的三个形状，现在**三个都答掉了**，而且每一个都是在下文**钉住**的、
    // 不是被描述一番：`/ai/chat` 的答案就是冻结的 `chat-response`、什么都没多加；effect 的项答冻结的
    // `traceItem`；审计链答它自己的对象。最后让步的是**聊天那条** —— 本运行时曾主张它的超集是一处**已声明
    // 的映射**，而契约、参照实现与协议散文**三者都不认同**。

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    private HttpHeaders asCaller(String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        return headers;
    }

    /** The {@code data} of a successful call, unwrapped from the envelope this same test pins. */
    private Object dataOf(ResponseEntity<String> response) {
        assertEquals(200, response.getStatusCode().value(), "the probe must be served: " + response);
        return Vectors.map(Json.parse(response.getBody())).get("data");
    }

    private Object getData(String path, String user) {
        return dataOf(rest.exchange(URI.create(path), HttpMethod.GET,
                new HttpEntity<>(asCaller(user)), String.class));
    }

    /**
     * A business write to make, since this runtime's own sample module only reads: its writes travel as
     * AI tool calls, and those are the AI chain's business rather than the operations one.
     */
    @TestConfiguration
    static class Probe {

        /** Registered by the component scan, not by a bean method — both at once is a double mapping. */
        @RestController
        static class ProbeController {

            @PostMapping("/probe/things")
            Map<String, Object> create() {
                return Map.of("created", true);
            }

            @PostMapping("/probe/things/{id}/touch")
            Map<String, Object> touch(@PathVariable String id) {
                return Map.of("touched", id);
            }
        }
    }

    /** One turn by this caller, answering the conversation it opened. */
    private String aConversationOf(String user) {
        ResponseEntity<String> response = rest.postForEntity("/ai/chat", new HttpEntity<>(
                Map.of("message", "给客户建一条跟进记录", "customerId", aCustomerOf(user)),
                asCaller(user)), String.class);
        return String.valueOf(Vectors.map(dataOf(response)).get("conversationId"));
    }

    @Test
    void everyConfirmationTheCallerIsShownMatchesTheFrozenItem() {
        // One proposed write, so the list has something in it; then the list itself, which is the
        // shape a console renders.
        rest.postForEntity("/ai/chat", new HttpEntity<>(
                Map.of("message", "给客户建一条跟进记录", "customerId", aCustomerOf("alice")), asCaller("alice")), String.class);

        Object data = getData("/ai/my/confirmations", "alice");

        List<?> items = (List<?>) data;
        assertFalse(items.isEmpty(), "the caller was shown nothing to conform to");
        for (Object item : items) {
            WireSchemas.assertConformsTo(item, "my-confirmation-item");
        }
    }
    @Test
    void theChatAnswerMatchesTheFrozenObject() {
        // Both shapes of the turn, because the superset this used to answer was wider on one of them
        // than the other: a message that routes to a read tool, and one that proposes a write. The
        // second is the case that argues for extra fields — a caller that wants to know a write is
        // waiting — and the object refuses them anyway, which is why the token has its own surface.
        //
        // 一个回合的**两种形状**都断言，因为这个超集在其中一个上比另一个更宽：一条路由到**读**工具的消息，
        // 和一条**提出写**的消息。第二种正是「多加字段」的主张所在 —— 一个想知道**有写在等**的调用方 ——
        // 而对象**照样**不接受它们，所以 token 有它自己的面。
        Object read = dataOf(rest.postForEntity("/ai/chat",
                new HttpEntity<>(Map.of("message", "分析客户风险", "customerId", aCustomerOf("alice")),
                        asCaller("alice")), String.class));
        WireSchemas.assertConformsTo(read, "chat-response");

        Object write = dataOf(rest.postForEntity("/ai/chat",
                new HttpEntity<>(Map.of("message", "给客户建一条跟进记录", "customerId", aCustomerOf("alice")),
                        asCaller("alice")), String.class));
        WireSchemas.assertConformsTo(write, "chat-response");
    }

    @Test
    void theAuditChainAnswerMatchesTheFrozenObject() {
        // Read as an administrator: the answer carries the rows themselves, which is who the chain is
        // for (`AuditController`), and the corpus replays the object with `actor: admin`.
        Object data = dataOf(rest.exchange(URI.create("/audit/verify"), HttpMethod.GET,
                new HttpEntity<>(asCaller("carol")), String.class));

        WireSchemas.assertConformsTo(data, "audit-chain-verification");
    }

    @Test
    void everySideEffectTheCallerCanSeeMatchesTheFrozenItem() {
        // An empty list would pass for the wrong reason, so the test makes an effect first: a write is
        // proposed, a person approves it, and what that leaves behind is what gets held to the schema.
        int held = Pending.count(rest, "alice", delegationSecret);
        rest.postForEntity("/ai/chat",
                new HttpEntity<>(Map.of("message", "给客户建一条跟进记录", "customerId", aCustomerOf("alice")),
                        asCaller("alice")), String.class);
        assertEquals(held + 1, Pending.count(rest, "alice", delegationSecret),
                "the write is proposed as something a person can decide");
        String token = Pending.token(rest, "alice", delegationSecret);
        Object decided = dataOf(rest.postForEntity("/ai/confirmations/" + token,
                new HttpEntity<>(Map.of("decision", "approve"), asCaller("alice")), String.class));
        assertEquals("executed", Vectors.map(decided).get("status"),
                "the approval must run the write, or there is no effect to hold to a schema: " + decided);

        List<?> items = (List<?>) Vectors.map(getData("/ai/tool-effects", "alice")).get("items");
        assertFalse(items.isEmpty(), "the approved write must appear as an effect");
        for (Object item : items) {
            WireSchemas.assertConformsTo(item, "side-effect-revoke");
        }
    }

    @Test
    void everyToolTheAdministratorIsShownMatchesTheFrozenInventory() {
        List<?> items = (List<?>) getData("/ai/tools", "carol");

        assertFalse(items.isEmpty(), "this runtime registers tools, so the inventory cannot be empty");
        for (Object item : items) {
            WireSchemas.assertConformsTo(item, "ai-tool-inventory");
        }
        // The governance half is asked rather than restated, so this pins it against the runtime's own
        // declarations: the built-in tools are R1, R3 and R4, and the gate turns those into auto,
        // confirm and approval. A list that described a tool one way and gated it another would fail
        // here — and so would one that copied a fixed string.
        //
        // 治理那一半是**问来的**、不是复述的，所以这里拿运行时**自己的声明**去钉：内置工具是 R1 / R3 / R4，
        // 而闸把它们变成 auto / confirm / approval。**描述成一样、把关成另一样**的列表会在这里挂；抄一个固定
        // 字符串的也会。
        List<String> gates = new ArrayList<>();
        for (Object item : items) {
            Map<?, ?> row = Vectors.map(item);
            gates.add(row.get("gateMode") + "/" + row.get("riskLevel"));
        }
        assertTrue(gates.contains("auto/R1"), "an R1 tool reads as auto: " + gates);
        assertTrue(gates.contains("confirm/R3"), "an R3 tool reads as confirm: " + gates);
        assertTrue(gates.contains("approval/R4"), "an R4 tool reads as approval: " + gates);

        Map<?, ?> twoPerson = items.stream().map(o -> Vectors.map(o))
                .filter(row -> "R4".equals(row.get("riskLevel")))
                .findFirst().orElseThrow(() -> new AssertionError("no R4 tool to check the gate against"));
        assertEquals(Boolean.TRUE, twoPerson.get("requiresApproval"),
                "R4 is the two-person gate: " + twoPerson);
        assertEquals(Boolean.TRUE, twoPerson.get("requiresConfirmation"),
                "and it still needs a decision: " + twoPerson);
        // Nothing here declares a per-tool switch, a role list or a permission set, and the inventory
        // says so rather than guessing: empty and null are what "not declared" looks like.
        //
        // 这里没有任何东西声明过逐工具的开关、角色表或权限集，而清单**如实这么答**而不是猜：空与 null 就是
        // 「没声明」的样子。
        for (Object item : items) {
            Map<?, ?> row = Vectors.map(item);
            assertEquals(Boolean.TRUE, row.get("enabled"), "a registered tool is enabled here: " + row);
            assertEquals(List.of(), row.get("allowedRoles"), "no role list is declared here: " + row);
            assertEquals(null, row.get("permissions"), "no permission set is declared here: " + row);
        }
    }

    @Test
    void theToolInventoryIsRefusedToAPlainCaller() {
        ResponseEntity<String> response = rest.exchange(URI.create("/ai/tools"), HttpMethod.GET,
                new HttpEntity<>(asCaller("alice")), String.class);

        assertEquals(403, response.getStatusCode().value(),
                "the inventory says what governance is doing to each tool, so it is an "
                        + "administrator's list: " + response);
    }

    @Test
    void everyConversationTheCallerIsShownMatchesTheFrozenData() {
        // One turn, so there is a conversation to read back; the list and the trace are both the
        // console's own calls.
        String conversationId = aConversationOf("alice");

        List<?> conversations = (List<?>) getData("/ai/conversations", "alice");

        assertFalse(conversations.isEmpty(), "the turn above opened a conversation");
        for (Object conversation : conversations) {
            WireSchemas.assertConformsTo(conversation, "conversation-data");
        }

        Map<String, Object> trace = Vectors.map(getData("/ai/conversations/" + conversationId + "/trace", "alice"));
        WireSchemas.assertConformsTo(trace.get("conversation"), "conversation-data");
        List<?> steps = (List<?>) trace.get("steps");
        assertFalse(steps.isEmpty(), "a conversation with a turn in it has a step to show");
        for (Object step : steps) {
            WireSchemas.assertConformsTo(step, "trace-step");
        }
        assertEquals("input", Vectors.map(steps.get(0)).get("type"),
                "the first step is the caller's own message: " + steps);
        // The governance half is linked now: the write this turn proposed left an audit row carrying
        // the conversation's id, so the trace places the tool call inside the conversation rather than
        // saying it cannot. This replaced the notice that used to stand here — the notice said the
        // half was unlinked, and it is retired because the link is recorded (see JV-62).
        //
        // 治理那一半现在有链了：这一回合提议的那次写留下了一行**带着会话 id** 的审计，故追踪把那次工具调用**放在
        // 会话里面**、而不是说放不进去。这正是**替换掉**原先那条 notice 的东西 —— notice 说的是那一半没有链，而
        // 它被撤下，正因为链已经记下（见 `JV-62`）。
        boolean linked = steps.stream().map(o -> Vectors.map(o))
                .anyMatch(step -> "tool_call".equals(step.get("type")));
        assertTrue(linked, "the trace must place the tool call this turn proposed: " + steps);
    }

    @Test
    void aTracePlacesTheWriteAConversationProposedAndTheEffectItLeft() {
        // The whole story of one conversation: a message proposes a write, a person approves it, and
        // the trace must show the call, the decision and the effect it produced — all linked by the id
        // recorded where the call was made.
        //
        // 一条会话的完整故事：一句消息提议一次写、有人批准它，而追踪必须显示出那次调用、那次裁决、以及它产出的
        // 副作用 —— 三者都由**调用发生时记下的那个 id** 拴上。
        String conversationId = aConversationOf("alice");
        String token = Pending.token(rest, "alice", delegationSecret);
        Object decided = dataOf(rest.postForEntity("/ai/confirmations/" + token,
                new HttpEntity<>(Map.of("decision", "approve"), asCaller("alice")), String.class));
        assertEquals("executed", Vectors.map(decided).get("status"),
                "the approval must run the write, or there is no effect step to look for: " + decided);

        Map<String, Object> trace =
                Vectors.map(getData("/ai/conversations/" + conversationId + "/trace", "alice"));
        List<?> steps = (List<?>) trace.get("steps");
        for (Object step : steps) {
            WireSchemas.assertConformsTo(step, "trace-step");
        }
        List<String> types = steps.stream()
                .map(o -> String.valueOf(Vectors.map(o).get("type")))
                .toList();
        assertTrue(types.contains("tool_call"), "the trace must place the call: " + types);
        assertTrue(types.contains("confirmation"), "the trace must place the decision: " + types);
        assertTrue(types.contains("effect"), "the trace must place the effect the write produced: " + types);
        assertTrue(types.indexOf("input") < types.indexOf("assistant"),
                "the turns keep their order: " + types);
    }

    @Test
    void aConversationOfAnotherCallerIsNotReadableAsATrace() {
        String conversationId = aConversationOf("alice");

        ResponseEntity<String> foreign = rest.exchange(
                URI.create("/ai/conversations/" + conversationId + "/trace"), HttpMethod.GET,
                new HttpEntity<>(asCaller("bob")), String.class);
        assertEquals(403, foreign.getStatusCode().value(),
                "another caller's conversation is refused, not shown: " + foreign);

        ResponseEntity<String> missing = rest.exchange(
                URI.create("/ai/conversations/no-such-conversation/trace"), HttpMethod.GET,
                new HttpEntity<>(asCaller("alice")), String.class);
        assertEquals(404, missing.getStatusCode().value(),
                "an unknown id is a 404, not a conversation invented on the spot — this runtime hands "
                        + "out its own ids, and the chat path is where a new one is made: " + missing);
    }

    @Test
    void aBusinessWriteLeavesAnOperationsRowAndAnAiTurnDoesNot() {
        rest.postForEntity("/probe/things", new HttpEntity<>(Map.of(), asCaller("alice")), String.class);
        rest.postForEntity("/probe/things/42/touch", new HttpEntity<>(Map.of(), asCaller("alice")), String.class);
        rest.postForEntity("/ai/chat", new HttpEntity<>(
                Map.of("message", "你好"), asCaller("alice")), String.class);

        List<?> items = (List<?>) Vectors.map(getData("/audit/operations/logs", "carol")).get("items");

        assertFalse(items.isEmpty(), "the write above left a row");
        for (Object item : items) {
            WireSchemas.assertConformsTo(item, "operation-audit-log-row");
        }
        Map<?, ?> created = items.stream().map(o -> Vectors.map(o))
                .filter(row -> String.valueOf(row.get("path")).endsWith("/probe/things"))
                .findFirst().orElseThrow(() -> new AssertionError("the probe's write is not in the log: " + items));
        assertEquals("CREATE", created.get("action"), "a POST creates: " + created);
        assertEquals("POST", created.get("method"), "and the row says how it arrived: " + created);
        assertNull(created.get("userId"),
                "the frozen row types `userId` as number|null, and this identity is not numeric: " + created);
        assertEquals("alice", created.get("username"),
                "and who did it — read before the security context is gone, carried in `username`: " + created);
        assertEquals(200, ((Number) created.get("statusCode")).intValue(),
                "and how it ended, since the row is written after the answer: " + created);

        Map<?, ?> touched = items.stream().map(o -> Vectors.map(o))
                .filter(row -> String.valueOf(row.get("path")).endsWith("/probe/things/42/touch"))
                .findFirst().orElseThrow(() -> new AssertionError("no row for the nested route: " + items));
        assertEquals("42", touched.get("targetId"),
                "the row names what the route named, off the handler's own variables: " + touched);
        // The AI surface keeps its own chain: a turn is a decision trace, not a business operation, and
        // this is where that decision is held rather than left to whoever reads the code next.
        //
        // AI 面有自己的链：一个回合是**决策轨迹**、不是**业务操作**；这个决定钉在这里，而不是留给下一个读代码的人。
        assertTrue(items.stream().map(o -> Vectors.map(o))
                        .noneMatch(row -> String.valueOf(row.get("path")).contains("/ai/")),
                "an AI turn must not appear as an operation: " + items);
    }

    @Test
    void theOperationsAuditIsRefusedToAPlainCaller() {
        ResponseEntity<String> response = rest.exchange(URI.create("/audit/operations/logs"), HttpMethod.GET,
                new HttpEntity<>(asCaller("alice")), String.class);

        assertEquals(403, response.getStatusCode().value(),
                "the operations chain is the administrator's, as the AI one is: " + response);
    }
}
