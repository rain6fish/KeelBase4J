// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolParameter;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

/**
 * One execution's three rows — intent, result, side effect — carry one id, in the cases where the
 * conversation and the clock cannot tell two executions apart.
 *
 * <p>The link this replaces was `conversation_id` + tool name + timestamps. That is enough when one
 * person makes one call at a time; it is not enough for two calls racing inside one conversation, for
 * the same tool called twice, or for a call with no conversation at all (a task run, an MCP caller).
 * Each test below therefore does more than count ids: it shows that the cohort the old key left
 * behind is ambiguous, and that the new key splits it. The reverse control is the point — an id that
 * is merely written everywhere would pass a "the rows have an id" assertion and prove nothing.
 *
 * <p><b>Measured reverse control, 2026-10-19.</b> Replacing the minted id with {@code null} in
 * {@code GovernedExecutionEngine#run} — one line, no schema change — turns all four tests below red,
 * and every failure is the correlation assertion rather than an incidental one: the concurrent case
 * reports "the execution id is what splits the cohort into exactly two attempts"
 * ({@code expected: <2> but was: <1>}); the same-tool case reports the result row's id is null; the
 * no-conversation case reports "the id is the only link they carry" ({@code expected: not <null>});
 * and the reuse case reports "the attempts are two, with different ids"
 * ({@code expected: <2> but was: <1>}). Restoring the line returns all four to green. The tests
 * therefore measure that the key is load-bearing, not merely that a column gets filled.
 *
 * <p>一次执行的三行 —— intent、结果、副作用 —— 带同一个 id，且恰好用在那三种「会话与时钟分不开两次执行」
 * 的情形上。
 *
 * <p>它替代的关联方式是 `conversation_id` + 工具名 + 时间戳。一个人一次只发一个调用时够用；同一条会话里两次
 * 调用**并发**、同一个工具**连调两次**、或一次**根本没有会话**的调用（任务运行、MCP 调用方）时不够。下面每个
 * 测试因此不止数 id：它先显示旧键留下的那一批行是**有歧义**的，再显示新键把它**分开**。反向对照才是要点 ——
 * 一个「到处都写了个 id」的实现能通过「这些行有 id」的断言，却什么都没证明。
 *
 * <p><b>2026-10-19 实测的反向对照。</b>把 `GovernedExecutionEngine#run` 里生成的 id 换成 `null` —— 一行、
 * 不动 schema —— 下面四个测试**全红**，且每一条失败都是**关联断言**、不是顺带的别处：并发那条报「the execution
 * id is what splits the cohort into exactly two attempts」（`expected: <2> but was: <1>`）；同工具两次那条报
 * 结果行的 id 为 null；无会话那条报「the id is the only link they carry」（`expected: not <null>`）；复用那条报
 * 「the attempts are two, with different ids」（`expected: <2> but was: <1>`）。把那一行改回去，四个**全部
 * 转绿**。因此这些测试度量的是**这个键确实承重**，而不只是「某一列被填上了」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExecutionIdLinksTheThreeRowsTest {

    private static final String TOOL = "probe_execution_link";
    private static final String RESULT_TYPE = "probe_execution_link_result";

    /**
     * Set by the concurrency test only: both probe calls wait here, which is what guarantees the two
     * executions are actually in flight together rather than one after the other.
     *
     * 只有并发测试会设置它：两次探针调用在这里会合，正是它保证两次执行**真的同时**在飞、而不是一前一后。
     */
    private static final AtomicReference<CyclicBarrier> RENDEZVOUS = new AtomicReference<>();

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    SideEffectRepository sideEffects;

    @AfterEach
    void clearRendezvous() {
        RENDEZVOUS.set(null);
    }

    @TestConfiguration
    static class Probe {

        private final AtomicLong resultIds = new AtomicLong(10_000);

        @Bean
        AiTool probeExecutionLinkTool() {
            return new AiTool() {
                @Override
                public String name() {
                    return TOOL;
                }

                @Override
                public String description() {
                    return "test double: a write whose executions can be observed one by one";
                }

                @Override
                public String riskLevel() {
                    return "R1";
                }

                @Override
                public String resultType() {
                    return RESULT_TYPE;
                }

                @Override
                public List<ToolParameter> parameters() {
                    return List.of(ToolParameter.optional("slot", ToolParameter.STRING,
                            "a value that makes two calls distinct"));
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    CyclicBarrier barrier = RENDEZVOUS.get();
                    if (barrier != null) {
                        try {
                            barrier.await(10, TimeUnit.SECONDS);
                        } catch (Exception failed) {
                            throw new IllegalStateException("the probe calls did not meet", failed);
                        }
                    }
                    return ToolResult.ok(Map.of("id", resultIds.incrementAndGet()));
                }
            };
        }
    }

    /**
     * Two executions in one conversation, overlapping in time. Each execution's two audit rows and its
     * effect share one id, and the two ids differ — so the same conversation and tool no longer decide
     * the pairing.
     *
     * 同一条会话里的两次执行，时间上重叠。每次执行的两行审计与它那一行副作用共享一个 id，而两个 id 不同 ——
     * 于是同一个会话、同一个工具不再决定配对。
     */
    @Test
    void concurrentExecutionsInOneConversationEachGetTheirOwnId() throws Exception {
        String caller = "exec-link-concurrent";
        String conversation = "conv-exec-link-concurrent";
        Principal principal = new Principal(caller, "user");
        RENDEZVOUS.set(new CyclicBarrier(2));

        ExecutionOutcome first;
        ExecutionOutcome second;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<ExecutionOutcome> a = pool.submit(() -> {
                start.await();
                return engine.execute(TOOL, Map.of("slot", "a"), principal, conversation);
            });
            Future<ExecutionOutcome> b = pool.submit(() -> {
                start.await();
                return engine.execute(TOOL, Map.of("slot", "b"), principal, conversation);
            });
            start.countDown();
            first = a.get(20, TimeUnit.SECONDS);
            second = b.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals("executed", first.status(), "the first concurrent write runs");
        assertEquals("executed", second.status(), "and so does the second");
        assertNotEquals(first.effectId(), second.effectId(), "they are two writes, not one");

        List<AuditLog> cohort = auditLogs.findByConversationIdOrderByIdAsc(conversation).stream()
                .filter(row -> TOOL.equals(row.getTool()))
                .toList();
        // The reverse control: the old key's cohort holds both attempts' rows and nothing in it says
        // which intent belongs to which result.
        assertEquals(4, cohort.size(),
                "conversation + tool holds both attempts' four audit rows; without an execution id "
                        + "nothing in that cohort pairs them");
        assertEquals(2, cohort.stream().map(AuditLog::getExecutionId).distinct().count(),
                "and the execution id is what splits the cohort into exactly two attempts");
        assertEquals(2, sideEffects.findByConversationIdOrderByIdAsc(conversation).size(),
                "the same cohort holds both effects");

        for (ExecutionOutcome outcome : List.of(first, second)) {
            SideEffect effect = sideEffects.findById(outcome.effectId()).orElseThrow();
            String id = effect.getExecutionId();
            assertNotNull(id, "the effect of this execution carries its id");
            assertEquals(List.of("attempted", "executed"),
                    auditLogs.findByExecutionIdOrderByIdAsc(id).stream()
                            .map(AuditLog::getOutcome).toList(),
                    "one execution = its intent row, then its result row, both under " + id);
            assertEquals(outcome.effectId(), effect.getId(),
                    "and the effect is the third row under the same id");
        }
    }

    /**
     * The same tool called twice, with different arguments: two executions, two ids, three rows each.
     * Without the id the four audit rows and two effects in one conversation form an ambiguous batch.
     *
     * 同一个工具连调两次、参数不同：两次执行、两个 id、每次三行。没有这个 id，同一条会话里的四行审计与两个
     * effect 就是一批分不清的行。
     */
    @Test
    void twoCallsToTheSameToolAreSeparatedByTheirIds() {
        String caller = "exec-link-twice";
        String conversation = "conv-exec-link-twice";
        Principal principal = new Principal(caller, "user");

        ExecutionOutcome first = engine.execute(TOOL, Map.of("slot", "one"), principal, conversation);
        ExecutionOutcome second = engine.execute(TOOL, Map.of("slot", "two"), principal, conversation);

        assertEquals("executed", first.status());
        assertEquals("executed", second.status());
        assertNotEquals(first.effectId(), second.effectId(), "different arguments write different effects");

        List<AuditLog> resultRows = auditLogs.findByConversationIdOrderByIdAsc(conversation).stream()
                .filter(row -> "executed".equals(row.getOutcome()))
                .toList();
        assertEquals(2, resultRows.size());
        String firstId = resultRows.get(0).getExecutionId();
        String secondId = resultRows.get(1).getExecutionId();
        assertNotNull(firstId);
        assertNotNull(secondId);
        assertNotEquals(firstId, secondId,
                "the same tool called twice is two attempts, so the ids must differ");

        for (String id : List.of(firstId, secondId)) {
            assertEquals(List.of("attempted", "executed"),
                    auditLogs.findByExecutionIdOrderByIdAsc(id).stream()
                            .map(AuditLog::getOutcome).toList(),
                    id + " selects exactly one attempt's intent and result");
            assertEquals(1, sideEffects.findByExecutionIdOrderByIdAsc(id).size(),
                    id + " selects exactly the effect that attempt wrote");
        }
        // The reverse control is the two ids above: grouped only by conversation + tool, all four
        // audit rows are identical in those columns, so the old key could pair them four ways.
        //
        // 反向对照就是上面两个不同的 id：只按「会话 + 工具」分组时，四行审计在这两列上完全相同，旧键能给出
        // 四种配对。
    }

    /**
     * A call with no conversation — a task run, an MCP caller — is linked by the id and by nothing
     * else. Two such executions still separate cleanly, which is the case the old key could not answer
     * at all: there is no conversation to narrow the table down.
     *
     * 没有会话的调用 —— 任务运行、MCP 调用方 —— 只由这个 id 串起来、别无他物。两次这样的执行仍能干净分开，
     * 这正是旧键根本答不了的情形：没有会话可以把表缩小。
     */
    @Test
    void callsWithoutAConversationAreStillLinkedByTheirIds() {
        String caller = "exec-link-solo";
        Principal principal = new Principal(caller, "user");

        ExecutionOutcome first = engine.execute(TOOL, Map.of("slot", "solo-one"), principal);
        ExecutionOutcome second = engine.execute(TOOL, Map.of("slot", "solo-two"), principal);

        assertEquals("executed", first.status());
        assertEquals("executed", second.status());

        List<AuditLog> mine = auditLogs.findAllByOrderByIdAsc().stream()
                .filter(row -> caller.equals(row.getUserId()))
                .toList();
        assertEquals(4, mine.size(), "two executions, two audit rows each");
        for (AuditLog row : mine) {
            assertNull(row.getConversationId(), "these calls belong to no conversation");
            assertNotNull(row.getExecutionId(), "and the id is the only link they carry");
        }
        List<String> ids = mine.stream().map(AuditLog::getExecutionId).distinct().toList();
        assertEquals(2, ids.size(), "the two executions do not collide on one id");

        for (String id : ids) {
            assertEquals(2, auditLogs.findByExecutionIdOrderByIdAsc(id).size(),
                    id + " selects its own intent and result, not the other execution's");
            List<SideEffect> effects = sideEffects.findByExecutionIdOrderByIdAsc(id);
            assertEquals(1, effects.size(), id + " selects exactly one effect");
            assertNull(effects.get(0).getConversationId(),
                    "and that effect is honestly conversation-less too");
        }
        assertEquals(2, sideEffects.findByUserIdOrderByIdDesc(caller).size());
    }

    /**
     * The idempotent-reuse branch is an execution too: it writes an intent row under a fresh id, then
     * its result row under the same id. It writes no effect row — the effect it points at belongs to
     * the earlier execution — so the attempt's id selects two rows, and the effect keeps the id of the
     * execution that actually wrote it. This is the asymmetry the entry settled: the id names the
     * attempt, and the reuse path no longer skips its intent row.
     *
     * 幂等复用分支也是一次执行：它用一个新 id 写 intent 行，再用同一个 id 写结果行。它不写 effect 行 ——
     * 它指向的 effect 属于更早的那次执行 —— 于是这次尝试的 id 选出两行，而 effect 保留**真正写下它**的那次
     * 执行的 id。这就是本项定下的那个不对称：id 指**这次尝试**，复用路径也不再跳过它的 intent 行。
     */
    @Test
    void theReusePathWritesAnIntentRowUnderItsOwnId() {
        String caller = "exec-link-reuse";
        String conversation = "conv-exec-link-reuse";
        Principal principal = new Principal(caller, "user");

        ExecutionOutcome first = engine.execute(TOOL, Map.of("slot", "same"), principal, conversation);
        ExecutionOutcome reused = engine.execute(TOOL, Map.of("slot", "same"), principal, conversation);

        assertEquals("executed", first.status());
        assertEquals("executed", reused.status());
        assertEquals(first.effectId(), reused.effectId(), "the second call reuses the effect");

        List<AuditLog> rows = auditLogs.findByConversationIdOrderByIdAsc(conversation);
        assertEquals(4, rows.size(),
                "each execution leaves an intent row and a result row — the reuse branch included");
        assertEquals(2, rows.stream().map(AuditLog::getExecutionId).distinct().count(),
                "and the attempts are two, with different ids");

        String reuseId = rows.stream()
                .filter(row -> row.getDetail().contains("reused"))
                .map(AuditLog::getExecutionId)
                .findFirst().orElseThrow();
        assertNotEquals(rows.get(0).getExecutionId(), reuseId,
                "the reusing attempt gets a fresh id, not the original execution's");
        assertEquals(List.of("attempted", "executed"),
                auditLogs.findByExecutionIdOrderByIdAsc(reuseId).stream()
                        .map(AuditLog::getOutcome).toList(),
                "the reuse branch now writes intent + result under its own id");
        assertTrue(sideEffects.findByExecutionIdOrderByIdAsc(reuseId).isEmpty(),
                "it adds no effect row: nothing was written on this path");
        assertEquals(1, sideEffects.findByConversationIdOrderByIdAsc(conversation).size());

        SideEffect effect = sideEffects.findByConversationIdOrderByIdAsc(conversation).get(0);
        String originalId = effect.getExecutionId();
        assertNotNull(originalId, "the effect keeps the id of the execution that wrote it");
        assertNotEquals(reuseId, originalId, "not the reusing attempt's");
        assertEquals(first.effectId(), effect.getId());
    }
}
