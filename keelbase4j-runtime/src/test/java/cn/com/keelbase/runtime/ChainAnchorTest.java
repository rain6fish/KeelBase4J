// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Vectors;
import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.audit.ChainAnchorCommand;
import cn.com.keelbase.runtime.audit.ChainAnchorService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The chain tail is read from the real chain, and the record's digest reproduces the contract's own
 * sample — the only way to know the bytes are the bytes a third party will check.
 *
 * <p>The anchor itself is not signed here, by decision: the private key never enters this runtime and
 * no SM2 implementation is on its classpath. So the assertions are about the two halves that do not
 * need either — what the tail is, and what the unsigned record's bytes are — plus the comparison
 * against a tail read back from a document.
 *
 * <p>Nothing here moves {@code packageHasExternalAnchor}: an unsigned payload is not a published
 * anchor, and the {@code chain-tail-deletion} gap must keep applying until a deployer publishes one
 * outside this runtime. That fact is pinned by
 * {@code AuditReaderSeamTest.theCoverageStatementDeclaresItsRegistryScopeAndGaps}; this test keeps
 * the producer from pretending otherwise.
 *
 * 链尾是从真链上读的，记录的摘要复现契约自带的样例 —— 这是确认字节就是第三方要核的那串字节的唯一办法。
 *
 * <p>锚本身在这里**不签**，这是裁定：私钥不进本运行时，classpath 上也没有 SM2 实现。故断言只覆盖不需要
 * 它们的那两半 —— 链尾是什么、未签名记录的字节是什么 —— 以及拿一份文档里的链尾做对照。
 *
 * <p>这里不改变 `packageHasExternalAnchor`：未签名载荷不是已发布的锚，`chain-tail-deletion` 缺口在部署方
 * 于域外真正发布一份之前必须继续适用。那个事实由 `AuditReaderSeamTest` 钉住；本测试负责不让产出方假装
 * 相反。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ChainAnchorTest {

    @Autowired
    AuditService audit;

    @Autowired
    AuditLogRepository audits;

    @Autowired
    ChainAnchorService anchors;

    @Test
    void theTailIsTheNewestChainedRowOfTheAiChain() {
        String who = "anchor-" + UUID.randomUUID();
        audit.append("tool_call", who, "first", false, "executed", "create_followup");
        AuditLog last = audit.append("tool_call", who, "second", false, "executed", "create_followup");

        ChainAnchorService.Tails tails = anchors.tails();

        assertNotNull(tails.aiAudit());
        assertEquals(audits.countByHashIsNotNull(), tails.aiAudit().rows());
        assertEquals(last.getId(), tails.aiAudit().lastId());
        assertEquals(last.getHash(), tails.aiAudit().lastHash());
        assertNull(tails.operationAudit(),
                "the operation audit is a table, not a chain — null is its honest half of the record");
    }

    /**
     * The contract ships a sample whose {@code rootDigest} was computed from its own fields; building
     * the same record from the same tails must land on the same digest. Anything else means this
     * runtime sorts or formats the bytes differently from the reference, and a third party recomputing
     * the digest would disagree about what was signed.
     */
    @Test
    void theRecordReproducesTheContractsOwnSample() {
        Map<String, Object> sample = Vectors.map(Vectors.read("schemas/v1/samples/audit-chain-anchor.json"));
        ChainAnchorService.Tails tails = ChainAnchorCommand.tailsOf(sample);

        Map<String, Object> built = ChainAnchorService.payloadOf(tails, Instant.parse((String) sample.get("observedAt")));

        assertEquals(sample.get("rootDigest"), built.get("rootDigest"),
                "the digest must be taken over the same bytes as the contract's sample");
        assertEquals(sample.get("date"), built.get("date"));
        assertEquals(sample.get("observedAt"), built.get("observedAt"));
        assertEquals(sample.get("period"), built.get("period"));
        assertFalse(built.containsKey("sm2"), "the payload this runtime hands out is unsigned");
    }

    /** A signed anchor, read back, carries the tails the comparison needs. */
    @Test
    void aSignedAnchorReadsBackIntoTheSameTails() {
        Map<String, Object> sample = Vectors.map(Vectors.read("schemas/v1/samples/audit-chain-anchor.json"));

        ChainAnchorService.Tails tails = ChainAnchorCommand.tailsOf(sample);

        assertNotNull(tails.aiAudit());
        assertEquals(1234L, tails.aiAudit().rows());
        assertEquals(98765L, tails.aiAudit().lastId());
        assertNotNull(tails.operationAudit());
        assertEquals(567L, tails.operationAudit().rows());
    }

    /** The live chain against its own tail: unchanged reads {@code ok}, later rows read {@code grew}. */
    @Test
    void theLiveChainComparesOkAndThenGrows() {
        String who = "anchor-" + UUID.randomUUID();
        audit.append("tool_call", who, "one", false, "executed", "create_followup");
        ChainAnchorService.Tail tail = anchors.tails().aiAudit();

        ChainAnchorService.Comparison unchanged =
                anchors.compare(new ChainAnchorService.Tails(tail, null));

        assertTrue(unchanged.ok());
        assertEquals(ChainAnchorService.OK, unchanged.aiAudit().status());
        assertEquals(ChainAnchorService.EMPTY, unchanged.operationAudit().status(),
                "both halves are null, which is \"no chained row\", and that is ok");

        audit.append("tool_call", who, "two", false, "executed", "create_followup");
        ChainAnchorService.Comparison grew = anchors.compare(new ChainAnchorService.Tails(tail, null));

        assertTrue(grew.ok());
        assertEquals(ChainAnchorService.GREW, grew.aiAudit().status());
        assertTrue(grew.reasons().stream().anyMatch(reason -> reason.contains("not a tail deletion")),
                "growth is not reported as anything that looks like a deletion: " + grew.reasons());
    }

    /** A tail that is not on this chain reads as a change — the comparison does not paper over it. */
    @Test
    void atailThatIsNotOnTheChainIsReported() {
        ChainAnchorService.Tails elsewhere = new ChainAnchorService.Tails(
                new ChainAnchorService.Tail(Long.MAX_VALUE - 1, Long.MAX_VALUE, "0".repeat(64)), null);

        ChainAnchorService.Comparison answer = anchors.compare(elsewhere);

        assertFalse(answer.ok());
        assertEquals(ChainAnchorService.TAIL_MISSING, answer.aiAudit().status());
        assertEquals(List.of(answer.aiAudit().reason()), answer.reasons());
    }
}
