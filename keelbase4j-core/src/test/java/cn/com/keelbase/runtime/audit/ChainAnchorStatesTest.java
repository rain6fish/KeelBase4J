// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.ChainAnchorService.ChainComparison;
import cn.com.keelbase.runtime.audit.ChainAnchorService.Tail;
import cn.com.keelbase.runtime.audit.ChainAnchorService.Tails;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The comparison's six states, and the record's shape, without a database.
 *
 * <p>The states are the whole answer of the comparison: "the tail is still there" is not one fact but
 * four different ones — a missing tail, a changed last row, a replaced tail and a chain that simply
 * grew — and a reader that got any two of them confused would draw the wrong conclusion about the
 * database. Each is asserted here against constructed tails; the database-backed path is asserted in
 * {@code ChainAnchorTest}.
 *
 * 对照的六种状态、以及记录的形状，不连库。
 *
 * <p>这六种状态就是对照的全部答案：「链尾还在」不是一个事实而是四个不同的 —— 尾部缺失、末行被改、
 * 尾部被替换、链只是变长了 —— 把其中任意两个读混，都会对数据库得出错误结论。这里用构造出来的链尾逐个断言；
 * 连库那条路在 `ChainAnchorTest` 里断言。
 */
class ChainAnchorStatesTest {

    private static final Tail ANCHORED = new Tail(3, 10, "h10");

    @Test
    void aTailThatIsStillThereIsOk() {
        ChainComparison answer = ChainAnchorService.compareOne("aiAudit", ANCHORED, ANCHORED, "h10");

        assertTrue(answer.ok());
        assertEquals(ChainAnchorService.OK, answer.status());
        assertEquals("", answer.reason(), "a clean answer carries no reason to explain it");
    }

    @Test
    void anAnchorOfNothingOverAChainOfNothingIsEmpty() {
        ChainComparison answer = ChainAnchorService.compareOne("aiAudit", null, null, null);

        assertTrue(answer.ok());
        assertEquals(ChainAnchorService.EMPTY, answer.status());
    }

    @Test
    void rowsWrittenAfterAnEmptyAnchorAreGrowthNotADeletion() {
        ChainComparison answer = ChainAnchorService.compareOne("aiAudit", null, new Tail(2, 7, "h7"), null);

        assertTrue(answer.ok());
        assertEquals(ChainAnchorService.GREW, answer.status());
        assertTrue(answer.reason().contains("not a deletion"), answer.reason());
    }

    @Test
    void aChainThatLostEveryRowHasAMissingTail() {
        ChainComparison answer = ChainAnchorService.compareOne("aiAudit", ANCHORED, null, null);

        assertFalse(answer.ok());
        assertEquals(ChainAnchorService.TAIL_MISSING, answer.status());
    }

    @Test
    void aChainWithFewerRowsHasAMissingTail() {
        ChainComparison answer =
                ChainAnchorService.compareOne("aiAudit", new Tail(5, 12, "h12"), new Tail(3, 10, "h10"), null);

        assertFalse(answer.ok());
        assertEquals(ChainAnchorService.TAIL_MISSING, answer.status());
        assertTrue(answer.reason().contains("missing 2"), answer.reason());
    }

    @Test
    void anAnchoredLastRowThatIsGoneIsAChangedLastRow() {
        // Deleting the anchored tail and then writing again: the row count did not shrink, so this is
        // not "tail-missing" — the row the anchor named is simply not there any more.
        //
        // 删掉锚定的末行之后又写了新行：行数没有变少，所以这不是「尾部缺失」—— 只是锚点名的那一行不在了。
        ChainComparison answer =
                ChainAnchorService.compareOne("aiAudit", ANCHORED, new Tail(4, 11, "h11"), null);

        assertFalse(answer.ok());
        assertEquals(ChainAnchorService.LAST_ROW_CHANGED, answer.status());
        assertTrue(answer.reason().contains("deleted"), answer.reason());
    }

    @Test
    void anAnchoredLastRowWithAnotherHashIsAChangedLastRow() {
        ChainComparison answer =
                ChainAnchorService.compareOne("aiAudit", ANCHORED, new Tail(4, 11, "h11"), "not-h10");

        assertFalse(answer.ok());
        assertEquals(ChainAnchorService.LAST_ROW_CHANGED, answer.status());
        assertTrue(answer.reason().contains("changed"), answer.reason());
    }

    @Test
    void theSameRowCountWithAnotherLastRowIsAReplacedTail() {
        ChainComparison answer =
                ChainAnchorService.compareOne("aiAudit", ANCHORED, new Tail(3, 11, "h11"), "h10");

        assertFalse(answer.ok());
        assertEquals(ChainAnchorService.TAIL_REPLACED, answer.status());
    }

    @Test
    void moreRowsWithTheAnchoredLastRowIntactIsGrowth() {
        ChainComparison answer =
                ChainAnchorService.compareOne("aiAudit", ANCHORED, new Tail(5, 12, "h12"), "h10");

        assertTrue(answer.ok());
        assertEquals(ChainAnchorService.GREW, answer.status());
    }

    @Test
    void thePayloadCarriesTheFiveFieldsAndNoSignature() {
        Map<String, Object> payload = ChainAnchorService.payloadOf(
                new Tails(ANCHORED, null), Instant.parse("2026-11-08T00:05:00.000Z"));

        assertEquals(List.of("chains", "date", "observedAt", "period", "rootDigest"),
                List.copyOf(payload.keySet()),
                "insertion order is the canonical order, and rootDigest closes the record");
        assertEquals("daily", payload.get("period"));
        assertEquals("2026-11-08", payload.get("date"));
        assertEquals("2026-11-08T00:05:00.000Z", payload.get("observedAt"));
        assertFalse(payload.containsKey("sm2"), "an unsigned payload is not an anchor");
        assertFalse(payload.containsKey("publishedAt"));

        @SuppressWarnings("unchecked")
        Map<String, Object> chains = (Map<String, Object>) payload.get("chains");
        assertEquals(List.of("aiAudit", "operationAudit"), List.copyOf(chains.keySet()));
        assertNull(chains.get("operationAudit"),
                "this runtime's operation audit is not chained, and null says exactly that");
        @SuppressWarnings("unchecked")
        Map<String, Object> ai = (Map<String, Object>) chains.get("aiAudit");
        assertEquals(List.of("lastHash", "lastId", "rows"), List.copyOf(ai.keySet()));
        assertEquals(3L, ai.get("rows"));
        assertEquals(10L, ai.get("lastId"));
    }
}
