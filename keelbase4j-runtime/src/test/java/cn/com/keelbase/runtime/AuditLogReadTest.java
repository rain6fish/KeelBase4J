// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.protocol.Vectors;
import cn.com.keelbase.protocol.WireSchemas;
import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.domain.FollowUp;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The chain can be read back: which rows a caller left, in a period, and only for those who may see
 * them.
 *
 * <p>Before this, the chain could be appended to and verified but not read. The repository holding
 * the rows was reachable only from inside its own package, so "which rows did this caller put on the
 * chain today" had no answer — the question that a run's operator asks first. This pins the three
 * things that make the answer usable rather than merely present: the filter actually narrows, the
 * period actually cuts, and the rows go only to an administrator.
 *
 * <p>The filter this deployment cannot apply is asserted too, and that one is about a failure mode
 * rather than a feature: the reference offers agent, organisation and denied views, this runtime
 * records none of those, and a filter accepted and silently not applied returns a list that looks
 * filtered and is not. Refusing is the only answer a caller can act on.
 *
 * 链能被读回来了：某个调用方在一段时间里留下了哪些行，且只给有权看见它们的人。
 *
 * <p>在此之前，链能追加、能校验，却**读不回来**。存着那些行的仓库只有**它自己那个包内**够得到，于是
 * 「这个调用方今天往链上留了哪几行」**没有答案** —— 而那是**一次运行的操作者最先问的问题**。这里钉住让答案
 * **可用**（而不只是**存在**）的三件事：筛选真的收窄了、时间段真的切了、行**只**给管理员。
 *
 * <p>本部署**施加不了**的那个筛选也一并断言了，而它讲的是一种**失效模式**、不是一项功能：参照实现还提供
 * agent、组织与「被拒」三个视图，本运行时**一个都不记**，而一个**被接受却悄悄没被施加**的筛选回出来的是一份
 * **看着像筛过、其实没有**的列表。**拒绝**是调用方唯一能据以行动的回答。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class AuditLogReadTest {

    /**
     * This deployment's administrator, and the reason the rows are readable at all.
     *
     * 本部署的管理员，也是这些行**之所以读得回来**的那个理由。
     */
    private static final String ADMIN = "carol";

    /**
     * An ordinary caller: they may act, and that does not make the chain a roster they may read.
     *
     * 一个普通调用方：他能动，而这**不**让这条链变成他可以读的花名册。
     */
    private static final String CALLER = "alice";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AuditService audit;

    @Autowired
    SideEffectRepository sideEffects;

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    CustomerRepository customers;

    @Autowired
    FollowUpRepository followUps;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    private ResponseEntity<String> get(String path, String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        return rest.exchange(URI.create(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /**
     * The {@code data} of a successful call — the array of rows.
     *
     * 一次成功调用的 {@code data} —— 那个行数组。
     */
    private List<?> rows(String query, String user) {
        ResponseEntity<String> response = get("/audit/logs" + query, user);
        assertEquals(200, response.getStatusCode().value(), "the read must be served: " + response);
        List<?> rows = (List<?>) Envelopes.data(Vectors.map(Json.parse(response.getBody())));
        for (Object row : rows) {
            assertOutcomeIsLegalForItsAction((Map<?, ?>) row);
        }
        return rows;
    }

    /**
     * The vocabulary's own grouping, read from the vendored vector rather than restated here: a test
     * that copied it would be the second home for a rule that has exactly one. The schema check beside
     * it cannot catch this — v5's enum admits null, so a row whose action carries no outcome, or the
     * wrong one, conforms. The vector is what names that mistake, and until this ran, nothing did.
     *
     * 词表自己那套分组，从 vendored 向量里读、而不是在这里重述 —— 抄一份的测试正会是那条**只有一个家**的
     * 规则的**第二个家**。它旁边那道 schema 校验抓不到这件事：v5 的 enum 允许 null，故一行「带不出结局」或
     * 「带错了结局」都**符合 schema**。**点名这个错误的是向量**，而在这段跑起来之前，没有任何东西在跑它。
     */
    private static void assertOutcomeIsLegalForItsAction(Map<?, ?> row) {
        Map<String, Object> vector = Vectors.map(Vectors.read("ai-audit-log-outcome-v1-vector.json"));
        Map<String, Object> legalByAction = Vectors.map(vector.get("legalByAction"));
        Object action = row.get("action");
        List<?> legal = (List<?>) legalByAction.getOrDefault(action, legalByAction.get("any-other-action"));
        assertTrue(legal.contains(row.get("outcome")),
                "the vocabulary does not let `" + action + "` answer `" + row.get("outcome")
                        + "` — see ai-audit-log-outcome-v1-vector.json");
    }

    @Test
    void anAdministratorReadsTheRowsOfOneCaller() {
        // A caller of this test's own, so the assertion is on rows this test put there and not on
        // whatever the rest of the suite left in a shared table.
        //
        // 一个本测试自己的调用方，故断言针对的是**本测试放进去的行**，而不是套件其余部分在**共用表**里留下的。
        String who = "reader-" + UUID.randomUUID();
        audit.append("tool_call", who, "first", false, "executed", "create_followup");
        audit.append("tool_call", who, "second", false, "executed", "create_followup");
        audit.append("tool_call", "other-" + UUID.randomUUID(), "someone else's row", false, "executed",
                "create_followup");

        List<?> found = rows("?userId=" + who, ADMIN);

        assertEquals(2, found.size(), "the filter must narrow the chain to one caller");
        // Newest first, which is how the reference's own listing orders the same rows.
        //
        // 最新的在前 —— 参照实现自己那份列表也按这个序排同样的行。
        assertEquals("second", ((Map<?, ?>) found.get(0)).get("detail"));
        assertEquals("first", ((Map<?, ?>) found.get(1)).get("detail"));
        for (Object row : found) {
            assertEquals(who, ((Map<?, ?>) row).get("userId"),
                    "no other caller's row may appear under this filter");
            WireSchemas.assertConformsTo(row, "ai-audit-log-row");
        }
    }

    @Test
    void aPeriodCutsTheRowsBothWays() {
        String who = "period-" + UUID.randomUUID();
        audit.append("tool_call", who, "recorded now", false, "executed", "create_followup");

        // Both sides, because a period filter that never excludes anything passes an "includes it"
        // assertion on its own.
        //
        // 两面都要，因为一个**从不排除任何东西**的时间筛选，自己就能让「包含它」那条断言通过。
        assertEquals(1, rows("?userId=" + who + "&since=2000-01-01", ADMIN).size(),
                "a period starting before the row must include it");
        assertTrue(rows("?userId=" + who + "&since=2099-01-01", ADMIN).isEmpty(),
                "a period starting after the row must exclude it");
    }

    @Test
    void anOutcomeFilterNarrowsToTheRowsThatWentWrong() {
        String who = "outcome-" + UUID.randomUUID();
        audit.append("tool_call", who, "the call went as asked", false, "executed", "create_followup");
        audit.append("tool_call", who, "the call did not", true, "failed", "create_followup");

        List<?> errors = rows("?userId=" + who + "&isError=true", ADMIN);

        assertEquals(1, errors.size(), "only the row recorded as an error may come back");
        assertEquals("the call did not", ((Map<?, ?>) errors.get(0)).get("detail"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) errors.get(0)).get("isError"));
    }

    /**
     * A row that belongs to no governed execution answers the optional field with null.
     *
     * <p>v6 made {@code executionId} optional, and this runtime writes the column for tool
     * executions only; a non-tool row (a chat turn here) has nothing to correlate and must not
     * borrow an id from a neighbouring row. The field is still projected so a client can read the
     * absence rather than guess at it.
     *
     * 不属于任何一次受治理执行的行，对这个可选字段答 null。
     *
     * <p>v6 把 {@code executionId} 定为可选，而本运行时只为工具执行写这一列；非工具行（这里是一轮 chat）
     * 没有可关联的东西，不得向相邻的行借一个 id。字段**仍然投影出来**，好让客户端读到的是**缺席本身**、
     * 而不是靠猜。
     */
    @Test
    void aRowThatBelongsToNoExecutionCarriesNoId() {
        String who = "no-exec-" + UUID.randomUUID();
        audit.append("chat", who, "a chat turn, not a tool execution");

        List<?> found = rows("?userId=" + who, ADMIN);

        assertEquals(1, found.size(), "the filter must narrow the chain to this caller's row");
        Map<?, ?> row = (Map<?, ?>) found.get(0);
        assertTrue(row.containsKey("executionId"),
                "the projection answers the field even when there is no execution to name");
        assertNull(row.get("executionId"), "a chat row belongs to no governed execution");
        WireSchemas.assertConformsTo(row, "ai-audit-log-row");
    }

    @Test
    void aCallerWhoIsNotAnAdministratorIsRefusedTheRows() {
        ResponseEntity<String> response = get("/audit/logs", CALLER);

        assertEquals(403, response.getStatusCode().value(),
                "the answer carries the rows themselves, so it is not readable by every caller");
    }

    @Test
    void aFilterThisDeploymentCannotApplyIsRefusedRatherThanIgnored() {
        // The reference offers all three; this runtime records none of what they filter on. Accepting
        // one and not applying it would return a list that looks filtered and is not, and nothing
        // downstream could tell the difference.
        //
        // 参照实现三个都提供，而本运行时**不记它们筛的任何东西**。接受一个却**不施加**它，回出来的是一份
        // **看着像筛过、其实没有**的列表，而下游分不出区别。
        for (String unsupported : List.of("agentId=someone", "orgId=1", "denied=true")) {
            ResponseEntity<String> response = get("/audit/logs?" + unsupported, ADMIN);
            assertEquals(400, response.getStatusCode().value(),
                    "`" + unsupported + "` cannot be honoured here, so it must be refused: "
                            + response);
        }
    }

    /**
     * The period's end and the business object narrow the chain on the server.
     *
     * <p>Before this, an auditor asking "everything that happened to customer #9005 last week" had to
     * scan a bounded window on the console side: the read face took a start but not an end, and no
     * object at all. Both are filters this deployment *can* apply — the end from the row's own time,
     * the object through the ledger row of the execution that touched it — so both are applied here
     * rather than refused. The two failure modes are pinned with them: an object nothing touched
     * answers "no rows" (not the whole chain), and half a filter is refused (not widened).
     *
     * 区间的终点与业务对象在服务端收窄链。
     *
     * <p>在此之前，审计员问「客户 #9005 上周发生过什么」时，只能在控制台一侧扫一段有界窗口：读面只有一个
     * 起点、没有终点，也完全没有对象。这两样都是本部署**能施加**的筛选 —— 终点取自行自己的时间，对象经那次
     * 碰过它的执行的台账行 —— 故这里**施加**它们，而不是拒绝。两种失效模式一并钉住：没有东西碰过的对象答
     * 「没有行」（不是整条链），半个过滤条件被拒绝（不是被放宽）。
     */
    @Test
    void thePeriodsEndAndTheBusinessObjectNarrowTheChain() {
        String who = "object-" + UUID.randomUUID();
        String wanted = "wanted-" + UUID.randomUUID();
        String other = "other-" + UUID.randomUUID();
        String wantedExecution = "exec-" + UUID.randomUUID();
        String otherExecution = "exec-" + UUID.randomUUID();
        audit.append("tool_call", who, "touched the customer", false, "executed", "create_followup",
                null, null, wantedExecution);
        audit.append("tool_call", who, "touched something else", false, "executed", "create_followup",
                null, null, otherExecution);
        sideEffects.save(new SideEffect("key-" + UUID.randomUUID(), who, "create_followup",
                "crm_customer", wanted, "local_compensate", "hash", null, null, null,
                wantedExecution));
        sideEffects.save(new SideEffect("key-" + UUID.randomUUID(), who, "create_followup",
                "crm_customer", other, "local_compensate", "hash", null, null, null,
                otherExecution));

        List<?> byObject = rows("?objectType=crm_customer&objectId=" + wanted, ADMIN);
        assertEquals(1, byObject.size(), "the object filter answers only the rows of that object");
        assertEquals("touched the customer", ((Map<?, ?>) byObject.get(0)).get("detail"));

        assertTrue(rows("?objectType=crm_customer&objectId=missing-" + UUID.randomUUID(), ADMIN)
                        .isEmpty(),
                "an object nothing touched is a no-match, not the whole chain");

        assertEquals(1,
                rows("?objectType=crm_customer&objectId=" + wanted + "&until=2099-01-01", ADMIN).size(),
                "a period ending after the row must include it");
        assertTrue(rows("?objectType=crm_customer&objectId=" + wanted + "&until=2000-01-01", ADMIN)
                        .isEmpty(),
                "a period ending before the row must exclude it");

        assertEquals(400, get("/audit/logs?objectType=crm_customer", ADMIN).getStatusCode().value(),
                "half an object filter must be refused rather than widened");
        assertEquals(400, get("/audit/logs?objectId=1", ADMIN).getStatusCode().value(),
                "half an object filter must be refused rather than widened");
    }

    /**
     * A row that names its object in its own sentence is found by that object when the ledger has
     * nothing — and only by that kind.
     *
     * <p>A connect test is neither a tool call nor a write: it produces no ledger row, so the ledger
     * road answers "no match" for the object it dialled. The row names it instead at the end of
     * {@code detail} with a typed key whose word is readable (` · object MCP connection #12`) — a
     * table name there would leave the export, where this text is drawn, refusing the whole file. The
     * fallback compares that word literally: another kind matches nothing, and a key that could not
     * have been written this way is not scanned for.
     *
     * 一行在**自己句末**点名对象的审计行，在台账什么都没有时按那条对象找得到 —— 且只按那一种。
     *
     * <p>一次连通测试既不是工具调用、也不是一次写：它不产生台账行，故台账那条路对它拨的那条对象只答
     * 「无匹配」。这一行改在 {@code detail} 末尾用一个带类型的键点名，键上的词是**可读的**
     * （` · object MCP connection #12`）—— 那里若是表名，这段会被画进的那份导出就会整份被拒。回落把词
     * **逐字**比较：另一种对象什么都不命中，而一对不可能这样写下的输入不会被拿去扫。
     */
    @Test
    void aRowThatNamesItsObjectInItsOwnSentenceIsFoundByThatObject() {
        String key = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
        String word = "MCP connection";
        AuditLog row = audit.append("mcp_connect_test", "1",
                "mcp test id=" + key + " result=ok · object " + word + " #" + key);

        List<?> found = rows("?objectType=MCP+connection&objectId=" + key, ADMIN);
        assertEquals(1, found.size(),
                "the sentence's typed key is the second road of the object filter");
        assertEquals(row.getId(), ((Number) ((Map<?, ?>) found.get(0)).get("id")).longValue(),
                "the row that names the object is the one the filter answers with");

        assertTrue(rows("?objectType=Another+kind&objectId=" + key, ADMIN).isEmpty(),
                "a key about one kind must not answer a search for another");
        assertTrue(rows("?objectType=MCP+connection&objectId=not-a-key", ADMIN).isEmpty(),
                "a business key that could not have been written this way is not scanned for");
        assertTrue(rows("?objectType=MCP+connection&objectId=" + key + "&until=2000-01-01", ADMIN)
                        .isEmpty(),
                "the period the caller gave bounds the sentence scan too");
    }

    /**
     * The typed key is judged exactly: it must end the sentence, its digits are 1–18, and a leading
     * zero is a character, not a number.
     *
     * <p>These are the same six cases the console pins in `audit-query.test.mjs` — trailing space,
     * line break and period are not the end; `#007` is not `#7`; eighteen digits are a key and
     * nineteen are not. The two read faces have to answer alike, so the cases live on both sides.
     *
     * 带类型的键按**恰好**判：必须收尾、数字 1–18 位、前导零是字符而不是数字。
     *
     * <p>这六个情形与控制台 `audit-query.test.mjs` 里钉的是同一组 —— 尾随空格、换行、句号都不算收尾；
     * `#007` 不是 `#7`；18 位是键、19 位不是。两条读面必须答得一样，故两侧各钉一份。
     */
    @Test
    void theTypedKeyUsesTheRuntimesDigitAndBoundaryRules() {
        String kind = "boundary-kind-" + UUID.randomUUID();
        String base = "probe · object " + kind + " #";
        audit.append("mcp_connect_test", "boundary", base + "12");
        audit.append("mcp_connect_test", "boundary", base + "12 ");
        audit.append("mcp_connect_test", "boundary", base + "12\n");
        audit.append("mcp_connect_test", "boundary", base + "12.");
        AuditLog zeroes = audit.append("mcp_connect_test", "boundary", base + "007");
        AuditLog eighteen = audit.append("mcp_connect_test", "boundary",
                base + "123456789012345678");
        audit.append("mcp_connect_test", "boundary", base + "1234567890123456789");

        List<?> exact = rows("?objectType=" + kind + "&objectId=12", ADMIN);
        assertEquals(1, exact.size(),
                "a trailing space, line break or period does not end the key");
        assertEquals("probe · object " + kind + " #12",
                ((Map<?, ?>) exact.get(0)).get("detail"));

        List<?> leadingZero = rows("?objectType=" + kind + "&objectId=007", ADMIN);
        assertEquals(1, leadingZero.size(), "#007 is a key of its own");
        assertEquals(zeroes.getId(),
                ((Number) ((Map<?, ?>) leadingZero.get(0)).get("id")).longValue());
        assertTrue(rows("?objectType=" + kind + "&objectId=7", ADMIN).isEmpty(),
                "#7 must not answer for #007");

        List<?> longKey = rows("?objectType=" + kind + "&objectId=123456789012345678", ADMIN);
        assertEquals(1, longKey.size(), "eighteen digits are still a key");
        assertEquals(eighteen.getId(),
                ((Number) ((Map<?, ?>) longKey.get(0)).get("id")).longValue());
        assertTrue(rows("?objectType=" + kind + "&objectId=1234567890123456789", ADMIN).isEmpty(),
                "nineteen digits are past the key's bound and are not scanned for");
    }

    /**
     * A write that landed still wins over a sentence that mentions the same object.
     *
     * <p>The fallback is the ledger's second road, not a competitor: when the pair has a ledger row,
     * that row's executions decide the answer, and a mention elsewhere on the chain is not added to
     * it. Anything else would turn "the writes that landed" into "everything that said the name".
     *
     * 已落地的那次写仍然压过链上另一处提到同一条对象的句子。
     *
     * <p>回落是台账的**第二条路**、不是竞争者：这一对在台账里有行时，由那次执行的行决定答案，链上别处的
     * 提及不会被并进去。否则「已落地的那些写」就变成了「凡说过这个名字的」。
     */
    @Test
    void theLedgerStillWinsOverASentenceThatNamesTheSameObject() {
        String who = "ledger-wins-" + UUID.randomUUID();
        String key = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
        String execution = "exec-" + UUID.randomUUID();
        audit.append("tool_call", who, "touched the customer", false, "executed", "create_followup",
                null, null, execution);
        sideEffects.save(new SideEffect("key-" + UUID.randomUUID(), who, "create_followup",
                "crm_customer", key, "local_compensate", "hash", null, null, null, execution));
        audit.append("mcp_connect_test", "1", "probe · object crm_customer #" + key);

        List<?> found = rows("?objectType=crm_customer&objectId=" + key, ADMIN);

        assertEquals(1, found.size(), "the ledger road decides; the mention is not added to it");
        assertEquals("touched the customer", ((Map<?, ?>) found.get(0)).get("detail"));
    }

    /**
     * A ledger hit under another caller does not hide this caller's row that names the object.
     *
     * <p>"The ledger selects nothing" has to mean nothing **inside the request's own scope**. Asked
     * unscoped, any write ever made to the object would suppress the sentence road for every caller
     * — and the caller who ran the probe would be told "no rows" about their own row.
     *
     * 别的调用方名下的台账命中，不得挡住本人点名对象的那一行。
     *
     * <p>「台账什么都没选出来」必须是**在本次请求自己的范围内**什么都没选出来。若不问范围，这条对象
     * 曾经有过任何一笔写，都会对所有调用方关掉句末路 —— 跑了探测的那个调用方会得到「没有行」。
     */
    @Test
    void aSentenceRowIsStillFoundWhenTheLedgerHitIsUnderAnotherCaller() {
        String other = "scope-other-" + UUID.randomUUID();
        String me = "scope-me-" + UUID.randomUUID();
        String kind = "scope-kind-" + UUID.randomUUID();
        String key = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
        String execution = "exec-" + UUID.randomUUID();
        audit.append("tool_call", other, "touched it", false, "executed", "create_followup",
                null, null, execution);
        sideEffects.save(new SideEffect("key-" + UUID.randomUUID(), other, "create_followup", kind,
                key, "local_compensate", "hash", null, null, null, execution));
        AuditLog mine = audit.append("mcp_connect_test", me, "probe · object " + kind + " #" + key);

        List<?> found = rows("?objectType=" + kind + "&objectId=" + key + "&userId=" + me, ADMIN);

        assertEquals(1, found.size(),
                "a write under another caller is outside this request's scope, so the sentence road runs");
        assertEquals(mine.getId(), ((Number) ((Map<?, ?>) found.get(0)).get("id")).longValue());
    }

    /**
     * A ledger hit outside the requested outcome does not hide an error row that names the object.
     *
     * <p>The outcome is part of the scope: a successful write answers "the ledger has it" for
     * `isError=false`, not for `isError=true`. Evaluating the ledger first without the outcome made
     * the error probe unfindable.
     *
     * 结果不匹配的台账命中，不得挡住点名这条对象的错误行。
     *
     * <p>结果是范围的一部分：一笔成功的写对 `isError=false` 答「台账有它」，对 `isError=true` 不答。
     * 不问结果就先判台账，会让那条错误探测行查不到。
     */
    @Test
    void aSentenceRowIsStillFoundWhenTheLedgerHitIsOutsideTheRequestedOutcome() {
        String who = "scope-outcome-" + UUID.randomUUID();
        String kind = "outcome-kind-" + UUID.randomUUID();
        String key = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
        String execution = "exec-" + UUID.randomUUID();
        audit.append("tool_call", who, "it worked", false, "executed", "create_followup",
                null, null, execution);
        sideEffects.save(new SideEffect("key-" + UUID.randomUUID(), who, "create_followup", kind,
                key, "local_compensate", "hash", null, null, null, execution));
        AuditLog probe = audit.append("mcp_connect_test", who, "probe · object " + kind + " #" + key,
                true);

        List<?> found = rows("?objectType=" + kind + "&objectId=" + key + "&isError=true", ADMIN);

        assertEquals(1, found.size(),
                "the successful write is outside isError=true, so the error row must answer");
        assertEquals(probe.getId(), ((Number) ((Map<?, ?>) found.get(0)).get("id")).longValue());
    }

    /**
     * A ledger hit outside the requested period does not hide a row inside it.
     *
     * <p>The period the caller gave bounds the sentence scan, so it must bound the ledger's answer
     * too. Otherwise a write made after the period's end would answer "the ledger has it" and the
     * probe written inside the period would be lost.
     *
     * 区间之外的台账命中，不得挡住区间内的行。
     *
     * <p>调用方给的区间限定了句末扫描，也就必须限定台账的答案。否则一笔写在区间终点之后，也会答成
     * 「台账有它」，而区间内写下的探测行会丢。
     */
    @Test
    void aSentenceRowIsStillFoundWhenTheLedgerHitIsOutsideTheRequestedPeriod()
            throws InterruptedException {
        String who = "scope-period-" + UUID.randomUUID();
        String kind = "period-kind-" + UUID.randomUUID();
        String key = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
        AuditLog probe = audit.append("mcp_connect_test", who, "probe · object " + kind + " #" + key);
        Instant cut = Instant.now();
        // The write lands after the period's end; the probe is inside it.
        // 这笔写在区间终点之后落地；探测行在区间之内。
        Thread.sleep(5);
        String execution = "exec-" + UUID.randomUUID();
        audit.append("tool_call", who, "touched it", false, "executed", "create_followup",
                null, null, execution);
        sideEffects.save(new SideEffect("key-" + UUID.randomUUID(), who, "create_followup", kind,
                key, "local_compensate", "hash", null, null, null, execution));

        List<?> found = rows("?objectType=" + kind + "&objectId=" + key + "&until=" + cut, ADMIN);

        assertEquals(1, found.size(),
                "the write after until is outside the period, so the probe inside it must answer");
        assertEquals(probe.getId(), ((Number) ((Map<?, ?>) found.get(0)).get("id")).longValue());
    }

    /**
     * An R4 write found by its object carries the approval row, so the six-question read can name the
     * second person who let it through.
     *
     * <p>Before this, object search selected only the rows carrying an execution id, and the approval
     * row carried none: the write was found and "who approved it" had no row to read. The approval now
     * names the execution it authorized, and the read face returns it beside the write. A block, a
     * pending proposal and a decline stay out — they are mentions of the object, not writes to it.
     *
     * 按对象找回来的一笔 R4 写带着那条批准行，于是六问读面点得出放行它的第二个人。
     *
     * <p>在此之前，对象检索只选带执行 id 的行，而批准行一个都不带：写找得到，「是谁批的」却没有一行可读。
     * 批准行现在点名它所授权的那次执行，读面把它与写一起回出来。拦截、待决与否决仍不在其中 —— 它们是对
     * 这条对象的提及，不是对它的写。
     */
    @Test
    void anApprovedWriteIsFoundByItsObjectWithTheRowThatNamesTheApprover() {
        Long customerId = customers.save(new Customer("Object Read Co", "low", "alice")).getId();
        ExecutionOutcome asked = engine.execute("escalate_customer", Map.of("customerId", customerId),
                new Principal("alice", "user"));
        assertEquals("requires_approval", asked.status(), "R4 waits for a second person");

        ExecutionOutcome approved = engine.decideApproval(asked.token(),
                new Principal("carol", "admin"), "approve");
        assertEquals("executed", approved.status(), "the second person's answer runs the write");

        FollowUp written = followUps.findByCustomerIdAndDeletedAtIsNull(customerId).stream()
                .findFirst().orElseThrow();
        List<?> found = rows("?objectType=follow_up&objectId=" + written.getId(), ADMIN);

        Map<?, ?> executed = found.stream()
                .map(row -> (Map<?, ?>) row)
                .filter(row -> "tool_call".equals(row.get("action"))
                        && "executed".equals(row.get("outcome")))
                .findFirst().orElse(null);
        Map<?, ?> approval = found.stream()
                .map(row -> (Map<?, ?>) row)
                .filter(row -> "tool_confirmation".equals(row.get("action"))
                        && "approved".equals(row.get("outcome")))
                .findFirst().orElse(null);

        assertTrue(executed != null, "the write's own rows are in the answer: " + found);
        assertTrue(approval != null,
                "the approval row must be beside the write it let through: " + found);
        assertEquals("carol", approval.get("userId"),
                "the fifth question reads the approver off this row");
        assertEquals(executed.get("executionId"), approval.get("executionId"),
                "one execution, one id: the decision and the attempt it started are the same execution");
    }
}
