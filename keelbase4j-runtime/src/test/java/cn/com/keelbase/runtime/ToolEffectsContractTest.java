// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The side-effect list, in the shape the runtime-neutral console reads it.
 *
 * <p>The console talks to two runtimes through one set of models. Its {@code ToolEffect} declares ten
 * required fields, and a runtime that answers with a bare array — or with items missing half of them
 * — is one the console cannot render, however correct the data is. This pins the contract on this
 * side of the wire so the two runtimes cannot drift apart without a test going red.
 *
 * <p>Asserting against a copied field list is deliberate: the point is that the fields the *console*
 * needs are present, so the list is written the way the console's model writes it, not the way this
 * runtime happens to produce it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class ToolEffectsContractTest {

    /**
     * The fields the console's {@code ToolEffect} declares as required. What the console does not
     * require is absent on purpose, and for two different reasons: the two relations among them
     * ({@code compensationGroup}, {@code parentEffectId}) stay absent because this runtime has no such
     * concepts, and inventing values would be worse than leaving the console's column empty — while a
     * snapshot is the other case, and the after-snapshot is now recorded at write time, which is what
     * lets the frozen {@code traceItem} be answered at all (see {@code WireShapeConformanceTest}).
     *
     * 控制台的 {@code ToolEffect} 声明为必需的那些字段。控制台**不**要求的刻意缺席，而理由有**两种**：
     * 其中的两个**关系**字段（{@code compensationGroup}、{@code parentEffectId}）缺席，是因为本运行时
     * **没有这些概念**，为它们编个取值比让控制台那一列为空更糟；而**快照**是另一种情形 —— after-snapshot
     * 现在**在写跑成时记下**，那正是冻结的 {@code traceItem} 能被回答的原因（见
     * {@code WireShapeConformanceTest}）。
     */
    private static final List<String> CONSOLE_REQUIRED_FIELDS = List.of(
            "id", "toolName", "conversationId", "userId", "resultType", "resultId",
            "argsHash", "createdAt", "targetExists", "targetSoftDeleted", "targetTitle");

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    CustomerRepository customers;

    @Autowired
    TestRestTemplate rest;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void theListAnswersTheConsolesEnvelopeAndFields() {
        // Produce a real effect through the trust loop, so the item under test is one the runtime
        // would actually have written.
        Principal alice = new Principal("alice", "user");
        Long customerId = customers.save(new Customer("Effects Co", "low", "alice")).getId();
        ExecutionOutcome pending = engine.execute("create_followup",
                Map.of("customerId", customerId, "note", "effects contract"), alice);
        engine.approve(pending.token(), alice);

        Map<String, Object> body = get("/ai/tool-effects?page=1&limit=20");

        assertTrue(body.containsKey("total"), "the console pages on total: " + body);
        assertEquals(1, body.get("page"), "and echoes the page it was asked for");
        assertEquals(20, body.get("limit"), "and the limit");
        List<?> items = (List<?>) body.get("items");
        assertNotNull(items, "items, not a bare array");
        assertTrue(!items.isEmpty(), "the effect just written should be listed");

        Map<?, ?> first = (Map<?, ?>) items.get(0);
        for (String field : CONSOLE_REQUIRED_FIELDS) {
            assertTrue(first.containsKey(field),
                    "the console's model requires '" + field + "'; got " + first.keySet());
        }
        assertEquals("alice", first.get("userId"),
                "the row says who the write was for — the value the column has always held");
        assertEquals("follow_up", first.get("resultType"));
        assertTrue((Boolean) first.get("targetExists"), "the follow-up it created is there");
        assertEquals(false, first.get("targetSoftDeleted"), "and is not soft-deleted");
        assertEquals("local_compensate", first.get("revokeClass"));
        assertEquals(true, first.get("revocable"), "an executed local effect is revocable");
        assertEquals("effects contract", first.get("targetTitle"),
                "the follow-up's note is what the console shows as the target's title");
    }

    @Test
    void thePageSizeIsCapped() {
        Map<String, Object> body = get("/ai/tool-effects?page=1&limit=100000");

        assertEquals(100, body.get("limit"),
                "a caller cannot ask for the whole table by asking for a big limit");
    }

    /**
     * One governed execution is one correlation key, and both surfaces carry it with the same value.
     *
     * <p>The effect list and the audit chain are written by the same call, but they are read through
     * two different projections; a client pairs them by {@code executionId} only if both project the
     * column. The contract made the field optional (v6), so a projection that forgot it would still
     * validate — which is why this asserts the pairing itself rather than the field's presence alone.
     *
     * 一次受治理执行就是一个关联键，而两个面带的是**同一个值**。
     *
     * <p>副作用清单与审计链由同一次调用写下，却经**两个不同的投影**读出；只有当两边都投影了那一列，
     * 客户端才能按 {@code executionId} 把它们配起来。契约把该字段定为可选（v6），故**漏掉它**的投影照样
     * 能通过校验 —— 这正是这里断言**配对本身**、而不只是字段存在的原因。
     */
    @Test
    void theEffectAndItsAuditRowCarryTheSameExecutionId() {
        Principal alice = new Principal("alice", "user");
        Long customerId = customers.save(new Customer("Execution Id Co", "low", "alice")).getId();
        ExecutionOutcome pending = engine.execute("create_followup",
                Map.of("customerId", customerId, "note", "execution id pairing"), alice);
        engine.approve(pending.token(), alice);

        Map<?, ?> effect = null;
        for (Object item : (List<?>) get("/ai/tool-effects?page=1&limit=100").get("items")) {
            if ("execution id pairing".equals(((Map<?, ?>) item).get("targetTitle"))) {
                effect = (Map<?, ?>) item;
                break;
            }
        }
        assertNotNull(effect, "the effect just written should be listed");
        Object executionId = effect.get("executionId");
        assertNotNull(executionId, "an effect written by a tool execution carries its execution id");

        // Read as the administrator, because the chain is not a roster every caller may read.
        // 以管理员身份读，因为这条链不是每个调用方都能读的花名册。
        List<?> auditRows = Envelopes.data(getAs("/audit/logs?userId=alice&limit=100", "carol"));
        long matching = auditRows.stream()
                .map(row -> (Map<?, ?>) row)
                .filter(row -> executionId.equals(row.get("executionId")))
                .count();
        assertEquals(2, matching,
                "the attempted and executed audit rows of that execution both carry the same key — "
                        + "and no other row does");
    }

    private Map<String, Object> get(String path) {
        return Envelopes.data(getAs(path, "alice"));
    }

    /** The raw envelope; a caller unwraps the {@code data} it expects with {@link Envelopes}. */
    private Object getAs(String path, String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        ResponseEntity<Map> res = rest.exchange(path, org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertEquals(200, res.getStatusCode().value(), path);
        return res.getBody();
    }
}
