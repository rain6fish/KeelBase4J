// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.com.keelbase.protocol.CanonicalJson;
import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.protocol.Vectors;
import cn.com.keelbase.protocol.WireSchemas;
import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.effect.WriteClaim;
import cn.com.keelbase.runtime.effect.WriteClaimReleaseService;
import cn.com.keelbase.runtime.effect.WriteClaimRepository;
import cn.com.keelbase.runtime.effect.WriteClaimService;
import cn.com.keelbase.runtime.governance.ExecutionAxis;
import cn.com.keelbase.runtime.identity.Principal;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * An operator can close a stranded write hold, and the act leaves one row on the chain (AW-16).
 *
 * <p>The hold itself already existed: a stale {@code claimed} row is taken back by the next identical
 * call, silently. What was missing is the line that says a person gave up on it — without that row,
 * "this hold was released" and "this hold is still in flight" read the same, and the release is
 * neither back-fillable nor visible. So the assertions here are about the row as much as about the
 * state: which action it carries, that it has no {@code outcome} (a release is not a verdict about
 * the write), and that it is readable on the chain face in the frozen row shape.
 *
 * <p>The other half is the refusal that makes the release safe: the key stays occupied. A release
 * that freed the key would turn "nobody knows whether the write landed" into "try again", which is
 * the second write the claim row exists to prevent.
 *
 * 运维可以关闭一份滞留的写占位，而这个动作在链上留下一行（AW-16）。
 *
 * <p>占位本身早就有：一份陈旧的 `claimed` 行会被下一个相同调用**静默**收回。缺的是「有个人放弃了它」那
 * 一行 —— 没有那一行，「这份占位被释放了」与「它还在飞」读起来一样，释放既不能事后补写、也看不见。故这里的
 * 断言既关于状态、也关于那一行：它带的是哪个 action、它**没有 `outcome`**（释放不是对那次写的判决）、
 * 以及它能不能在链的读面上按冻结的行形状被读到。
 *
 * <p>另一半是让释放安全的那个拒绝：键仍被占住。一次放走键的释放，会把「没人知道那次写落没落地」变成
 * 「再试一次」，而那正是这条占位行存在的理由所要挡住的第二次写。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class ClaimReleaseTest {

    private static final Principal ALICE = new Principal("alice", Principal.ROLE_USER);

    /** The test fixture's administrator — the identity that holds {@code manage} on the ledger. */
    private static final String ADMIN = "carol";

    private static final Principal CAROL = new Principal(ADMIN, Principal.ROLE_ADMIN);

    private static final String TOOL = "create_followup";

    @MockitoSpyBean
    AuditService audit;

    @Autowired
    WriteClaimService claims;

    @Autowired
    WriteClaimRepository claimRows;

    @Autowired
    WriteClaimReleaseService releases;

    @Autowired
    AuditLogRepository auditRows;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    TestRestTemplate rest;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    /**
     * The whole act, through the endpoint the operator actually calls, and then the two properties
     * that make it worth a row: the chain has it, in the frozen shape, and the key is still held.
     */
    @Test
    void anOperatorClosingAStaleHoldLeavesOneChainedRowAndKeepsTheKey() {
        String args = aCall();
        String marker = "note-" + UUID.randomUUID();
        WriteClaim hold = strandedHold(args);

        ResponseEntity<String> response = release(ADMIN, hold.getId(), marker, "ticket AW-16");

        Map<String, Object> answer = Envelopes.data(Json.parse(response.getBody()));
        assertEquals(WriteClaim.CLOSED_UNKNOWN, answer.get("status"),
                "the answer reports the handling decision, not the truth of the write: " + answer);
        assertEquals(Boolean.TRUE, answer.get("released"));
        Long rowId = ((Number) answer.get("auditRowId")).longValue();
        assertNotNull(rowId, "the act left a row: " + answer);

        AuditLog row = auditRows.findById(rowId).orElseThrow();
        assertEquals("claim_release", row.getAction());
        assertEquals(ADMIN, row.getUserId(), "the row names the operator who acted");
        assertNull(row.getOutcome(), "a release is not a verdict, so the row carries no outcome");
        assertEquals(TOOL, row.getTool());
        assertTrue(row.getDetail().contains(marker), "the note is on the row: " + row.getDetail());
        assertTrue(row.getDetail().contains("ticket AW-16"),
                "the evidence reference is on the row: " + row.getDetail());

        assertEquals(WriteClaim.CLOSED_UNKNOWN,
                claimRows.findById(hold.getId()).orElseThrow().getStatus());

        WriteClaimService.Outcome again = claims.claim(ALICE, TOOL, args);
        assertFalse(again.won(), "a released hold still occupies its key — release is not a retry");
        assertEquals(WriteClaim.CLOSED_UNKNOWN, again.status());

        Map<?, ?> listed = listedRow(rowId);
        WireSchemas.assertConformsTo(listed, "ai-audit-log-row");
        Map<String, Object> vector =
                Vectors.map(Vectors.read("ai-audit-log-outcome-v3-vector.json"));
        Map<String, Object> legalByAction = Vectors.map(vector.get("legalByAction"));
        List<?> legal = (List<?>) legalByAction.getOrDefault("claim_release",
                legalByAction.get("any-other-action"));
        assertTrue(legal.contains(listed.get("outcome")),
                "the vocabulary lets `claim_release` answer only null: " + listed);
    }

    /** A hold still inside its lease may be running right now, so releasing it is refused. */
    @Test
    void aHoldStillInsideItsLeaseIsNotReleasable() {
        String args = aCall();
        String marker = "note-" + UUID.randomUUID();
        WriteClaim hold = hold(args);

        ResponseStatusException refused = expectRefusal(hold.getId(), marker);
        assertEquals(409, refused.getStatusCode().value());
        assertTrue(refused.getReason().startsWith("not_stale"), refused.getReason());
        assertEquals(0, rowsMentioning(marker));
        assertEquals(WriteClaim.CLAIMED,
                claimRows.findById(hold.getId()).orElseThrow().getStatus());
    }

    /** A settled hold has a recorded effect; there is nothing unknown to release. */
    @Test
    void aSettledHoldIsNotReleasable() {
        String args = aCall();
        String marker = "note-" + UUID.randomUUID();
        WriteClaim hold = hold(args);
        claims.settle(ALICE, TOOL, args, null);

        ResponseStatusException refused = expectRefusal(hold.getId(), marker);
        assertEquals(409, refused.getStatusCode().value());
        assertTrue(refused.getReason().startsWith("not_claimable"), refused.getReason());
        assertEquals(0, rowsMentioning(marker));
    }

    /** Releasing twice is one act: the second answer says so and writes no second row. */
    @Test
    void aSecondReleaseWritesNoSecondRow() {
        String args = aCall();
        String marker = "note-" + UUID.randomUUID();
        WriteClaim hold = strandedHold(args);

        release(ADMIN, hold.getId(), marker, null);
        assertEquals(1, rowsMentioning(marker));

        ResponseStatusException refused = expectRefusal(hold.getId(), marker);
        assertEquals(409, refused.getStatusCode().value());
        assertTrue(refused.getReason().startsWith("already_closed"), refused.getReason());
        assertEquals(1, rowsMentioning(marker), "a repeated release is not a second act");
    }

    /** The refusal that keeps this an operator's act: an ordinary caller gets 403 and no row. */
    @Test
    void anOrdinaryCallerMayNotCloseAHold() {
        String args = aCall();
        String marker = "note-" + UUID.randomUUID();
        WriteClaim hold = strandedHold(args);

        ResponseEntity<String> refused = release("alice", hold.getId(), marker, null);

        assertEquals(403, refused.getStatusCode().value(), "only the ledger's manager may release");
        assertEquals(0, rowsMentioning(marker));
        assertEquals(WriteClaim.CLAIMED,
                claimRows.findById(hold.getId()).orElseThrow().getStatus());
    }

    /** A release nobody can explain is refused: the note is what the row exists to carry. */
    @Test
    void aReleaseWithoutAUsableNoteIsRefused() {
        String args = aCall();
        WriteClaim hold = strandedHold(args);

        ResponseEntity<String> shortNote = release(ADMIN, hold.getId(), "short", null);
        assertEquals(400, shortNote.getStatusCode().value(), shortNote.toString());
        assertEquals(WriteClaim.CLAIMED,
                claimRows.findById(hold.getId()).orElseThrow().getStatus());
        ResponseEntity<String> missing = release(ADMIN, hold.getId() + 100_000L, "no such claim here", null);
        assertEquals(404, missing.getStatusCode().value(), missing.toString());
    }

    /**
     * A chain that refuses the row must refuse the release too: the status change rolls back with it,
     * so "the hold is closed but the chain does not say so" cannot be left behind — and the retry,
     * which is the only repair the API offers, still works.
     *
     * <p>This is the failure the release path used to have: it committed the close first and appended
     * in a second transaction, so a failed append left the ledger closed and the chain empty, and a
     * retry answered {@code already_closed}.
     *
     * 链拒绝那一行时，释放也必须被拒：状态改动随它一起回滚，故「占位已关而链上没说」留不下来 ——
     * 而重试（API 唯一提供的补救）仍然能用。
     *
     * <p>这正是释放路径此前的失败：它先提交关闭、再以另一个事务追加，于是失败的追加留下「账本已关、
     * 链上为空」，重试答 `already_closed`。
     */
    @Test
    void aFailedChainWriteRollsTheCloseBackAndTheRetryIsTheRepair() {
        String args = aCall();
        String marker = "note-" + UUID.randomUUID();
        WriteClaim hold = strandedHold(args);

        doThrow(new IllegalStateException("the audit chain head row is missing"))
                .when(audit).append(anyString(), anyString(), anyString(), anyBoolean(), isNull(),
                        anyString());

        assertThrows(IllegalStateException.class,
                () -> releases.release(hold.getId(), CAROL, marker, null),
                "an append that fails must fail the release, not leave it half-recorded");

        assertEquals(WriteClaim.CLAIMED,
                claimRows.findById(hold.getId()).orElseThrow().getStatus(),
                "the status change rolled back with the row it could not be recorded with");
        assertEquals(0, rowsMentioning(marker), "no half-written line reached the chain");

        reset(audit);
        WriteClaimReleaseService.Released retried = releases.release(hold.getId(), CAROL, marker, null);
        assertEquals(WriteClaim.CLOSED_UNKNOWN, retried.status(), "the retry is the repair");
        assertNotNull(retried.auditRowId(), "and it leaves the row the first attempt could not");
        assertEquals(1, rowsMentioning(marker));
    }

    /**
     * The boundary answer names what actually happened. When the conditional update matches nothing
     * but the row still reads {@code claimed}, the age condition is what refused the release — the
     * row is at the lease edge, or was reclaimed and is fresh again — so the kind is {@code
     * not_stale}. It used to answer {@code not_claimable}, which named a state that never changed.
     *
     * 边界处的答案点名真正发生的事。条件更新一行没匹配上、而行仍读作 `claimed` 时，拒绝这次释放的是
     * **年龄条件** —— 这一行卡在租约边缘，或已被收回、又变新鲜 —— 故种类是 `not_stale`。它此前答的是
     * `not_claimable`，点名了一个从未改变的状态。
     */
    @Test
    void aRefusalFromTheAgeConditionSaysNotStale() {
        WriteClaim staleRow = mock(WriteClaim.class);
        when(staleRow.getId()).thenReturn(9L);
        when(staleRow.getStatus()).thenReturn(WriteClaim.CLAIMED);
        when(staleRow.getClaimedAt())
                .thenReturn(Instant.now().minusMillis(ExecutionAxis.LEASE_MILLIS + 1_000L));
        when(staleRow.getUserId()).thenReturn(ALICE.userId());
        when(staleRow.getToolName()).thenReturn(TOOL);

        WriteClaimRepository rows = mock(WriteClaimRepository.class);
        when(rows.findById(9L)).thenReturn(Optional.of(staleRow));
        when(rows.closeUnknown(eq(9L), anyString(), anyString(), any(Instant.class))).thenReturn(0);

        AuditService chain = mock(AuditService.class);
        WriteClaimReleaseService service =
                new WriteClaimReleaseService(rows, chain, mock(OwnershipGuard.class));

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> service.release(9L, CAROL, "a long enough note", null));

        assertEquals(409, refused.getStatusCode().value(), refused.toString());
        assertTrue(refused.getReason().startsWith("not_stale"), refused.getReason());
        verify(chain, never()).append(anyString(), anyString(), anyString(), anyBoolean(), isNull(),
                anyString());
    }

    private WriteClaim hold(String args) {
        assertTrue(claims.claim(ALICE, TOOL, args).won());
        return claimRows.findByIdempotencyKey(WriteClaimService.keyFor(ALICE, TOOL, args))
                .orElseThrow();
    }

    private WriteClaim strandedHold(String args) {
        WriteClaim hold = hold(args);
        transactions.executeWithoutResult(status -> entityManager
                .createQuery("update WriteClaim c set c.claimedAt = :old where c.id = :id")
                .setParameter("old", Instant.now().minusMillis(ExecutionAxis.LEASE_MILLIS + 1_000L))
                .setParameter("id", hold.getId())
                .executeUpdate());
        return hold;
    }

    private ResponseStatusException expectRefusal(Long claimId, String marker) {
        try {
            releases.release(claimId, CAROL, marker, null);
        } catch (ResponseStatusException refused) {
            return refused;
        }
        throw new AssertionError("the release should have been refused");
    }

    private ResponseEntity<String> release(String user, Long claimId, String note,
                                           String evidenceRef) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("note", note);
        payload.put("evidenceRef", evidenceRef);
        String body = CanonicalJson.json(payload);
        return rest.exchange(URI.create("/ai/tool-effects/claims/" + claimId + "/release"),
                HttpMethod.POST, new HttpEntity<>(body, headers(user)), String.class);
    }

    private Map<?, ?> listedRow(Long rowId) {
        ResponseEntity<String> response = rest.exchange(
                URI.create("/audit/logs?userId=" + ADMIN + "&limit=100"), HttpMethod.GET,
                new HttpEntity<>(headers(ADMIN)), String.class);
        assertEquals(200, response.getStatusCode().value(), "the read must be served: " + response);
        List<?> rows = Envelopes.data(Json.parse(response.getBody()));
        return rows.stream()
                .map(row -> (Map<?, ?>) row)
                .filter(row -> row.get("id") instanceof Number id
                        && id.longValue() == rowId)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the release row is on the chain face; saw " + rows.size()
                                + " rows, first: " + (rows.isEmpty() ? "none" : rows.get(0))));
    }

    private long rowsMentioning(String marker) {
        return auditRows.findAll().stream()
                .filter(row -> "claim_release".equals(row.getAction()))
                .filter(row -> row.getDetail() != null && row.getDetail().contains(marker))
                .count();
    }

    private HttpHeaders headers(String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        return headers;
    }

    /** Arguments unique to one test: the claim is keyed by the call's content, and the DB is shared. */
    private static String aCall() {
        return "{\"note\":\"" + UUID.randomUUID() + "\"}";
    }
}
