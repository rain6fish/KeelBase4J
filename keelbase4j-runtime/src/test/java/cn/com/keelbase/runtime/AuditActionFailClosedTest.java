// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.com.keelbase.protocol.UnknownAuditActionException;
import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.audit.AuditService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * A row whose action is not on the contract's vocabulary is refused, not written.
 *
 * <p>The vocabulary is closed, and this runtime used to accept anything: {@code append} would chain
 * whatever word it was handed, so a caller still writing a pre-contract word such as
 * {@code mcp_connect_test} put it on the chain as if the protocol knew it. A row no consumer can
 * classify is worse than a failed call — the failure is visible where it happens, the row is not. So
 * the refusal is pinned two ways here: the exception type a caller can catch, and the row count that
 * proves nothing landed behind it.
 *
 * <p>The other half is that the guard does not overreach: every word this runtime and its generated
 * applications actually write — {@code tool_call}, {@code tool_confirmation}, {@code effect_revoke},
 * {@code claim_release} — still goes in, and so does {@code analyze}, which is the contract word the
 * host moved a connect test to.
 *
 * 一行如果动作不在契约词表上，就**被拒绝**，而不是被写下。
 *
 * <p>词表是闭的，而本运行时过去什么都收：{@code append} 拿到什么词就把它串上链，于是仍在写契约前旧词
 * （如 {@code mcp_connect_test}）的调用方，等于把它写得像协议认识它一样。**没有消费方能归类**的行，
 * 比一次失败的调用更糟 —— 失败在它发生的地方看得见，而那行看不见。故这里的拒绝钉住两件事：
 * 调用方接得住的**异常类型**，以及证明它背后**什么都没落下**的行数。
 *
 * <p>另一半是这道闸**没有越界**：本运行时与它生成的应用真正写下的每一个词 —— {@code tool_call}、
 * {@code tool_confirmation}、{@code effect_revoke}、{@code claim_release} —— 照样进得去；
 * {@code analyze} 也进得去，那正是宿主把连通测试改用的契约词。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuditActionFailClosedTest {

    @Autowired
    AuditService audit;

    @Autowired
    AuditLogRepository auditLogs;

    @Test
    void anUnknownActionIsRefusedAndNothingLandsOnTheChain() {
        long before = auditLogs.count();

        UnknownAuditActionException refused = assertThrows(UnknownAuditActionException.class,
                () -> audit.append("not_a_contract_word", "1", "the chain must not take this"));

        assertEquals("not_a_contract_word", refused.action());
        assertEquals(before, auditLogs.count(),
                "a refused append must leave no row behind — refuse, do not store");
    }

    @Test
    void theOldHostWordIsRefusedRatherThanStored() {
        long before = auditLogs.count();

        assertThrows(UnknownAuditActionException.class,
                () -> audit.append("mcp_connect_test", "1",
                        "mcp test id=12 result=ok · object MCP connection #12"));

        assertEquals(before, auditLogs.count(),
                "a host still writing the pre-contract word must fail loudly, not silently land");
    }

    @Test
    void aNullActionIsRefused() {
        long before = auditLogs.count();

        assertThrows(UnknownAuditActionException.class,
                () -> audit.append(null, "1", "no action at all"));

        assertEquals(before, auditLogs.count());
    }

    @Test
    void everyWordThisRuntimeWritesStillGoesIn() {
        String who = "vocab-" + UUID.randomUUID();
        List<String> written = List.of("tool_call", "tool_confirmation", "effect_revoke",
                "claim_release", "analyze");

        for (String word : written) {
            AuditLog row = audit.append(word, who, word + " row", false, null, null);
            assertNotNull(row, "a known action must be written: " + word);
            assertEquals(word, row.getAction(), "the row carries the word it was given");
        }
    }
}
