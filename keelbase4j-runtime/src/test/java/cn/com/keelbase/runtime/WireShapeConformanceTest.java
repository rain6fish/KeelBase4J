// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
