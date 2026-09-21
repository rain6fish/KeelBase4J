// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.com.keelbase.protocol.Vectors;
import cn.com.keelbase.runtime.audit.AuditChainHead;
import cn.com.keelbase.runtime.audit.AuditChainHeadRepository;
import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * Conformance: reproduce {@code failure-semantics-v1-vector.json}.
 *
 * <p>Each outcome in the vector is one failure disposition with a frozen invariant. This class
 * drives the same invariant through the Java runtime, so the runtime's failure behaviour is pinned
 * to the contract instead of to whatever the code happens to do. Every vector id must be handled —
 * a new outcome in the vector fails here rather than passing unnoticed.
 *
 * <p>Honest boundary (not reproduced — the spike has no surface for it):
 * <ul>
 *   <li>{@code timeout} — the spike's tools make no external calls, so the <em>boundedness</em> of a
 *       real upstream timeout is not exercised; what is reproduced is the wire half: a failed call
 *       surfaces as a failure and records no side effect (never a fake success).</li>
 * </ul>
 * The main repo's gate additionally checks that each {@code wireSchema} is a frozen registry object;
 * the registry is not vendored here, so only the presence of the binding is asserted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FailureSemanticsTest {

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    SideEffectService sideEffects;

    @Autowired
    SideEffectRepository sideEffectRepository;

    @Autowired
    CustomerRepository customers;

    @Autowired
    FollowUpRepository followUps;

    @TestConfiguration
    static class Probe {

        /** Stands in for a tool whose result comes from an upstream call (2xx-empty / failure). */
        @Bean
        AiTool probeUpstreamTool() {
            return new AiTool() {
                @Override
                public String name() {
                    return "probe_upstream";
                }

                @Override
                public String description() {
                    return "test double: a tool whose upstream returned an empty body or failed";
                }

                @Override
                public String riskLevel() {
                    return "R1";
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    return switch (String.valueOf(args.get("mode"))) {
                        case "empty" -> ToolResult.ok(null);
                        case "fail" -> ToolResult.fail("upstream timeout after 3000ms");
                        default -> throw new IllegalArgumentException("unknown mode: " + args.get("mode"));
                    };
                }
            };
        }
    }

    @TestFactory
    List<DynamicTest> reproducesFrozenFailureSemantics() {
        Map<String, Object> doc = Vectors.map(Vectors.read("failure-semantics-v1-vector.json"));
        return Vectors.dynamic(Vectors.list(doc, "outcomes"), c -> {
            Map<String, Object> outcome = Vectors.map(c);
            String id = Vectors.str(outcome, "id");
            assertNotNull(Vectors.str(outcome, "invariant"), "every outcome must carry its invariant");
            assertNotNull(Vectors.str(outcome, "wireSchema"), "every outcome must name its wire carrier");
            switch (id) {
                case "timeout" -> timeoutSurfacesAsFailureNotFakeSuccess();
                case "unavailable" -> unavailableIsNeverReportedAsRevoked();
                case "unknown_empty" -> emptyUpstreamIsCarriedAsNullNotFabricated();
                case "duplicate_idempotent" -> repeatedCallReusesTheSameEffect();
                case "unique_conflict_idempotent" -> duplicateEffectKeyCannotFork();
                case "db_error_rethrow" -> dbErrorPropagatesInsteadOfMaskingAsIdempotentHit();
                case "confirmation_replay_rejected" -> replayedConfirmationTokenIsRejected();
                case "audit_fail_closed" -> auditWriteFailurePropagates();
                default -> fail("unhandled failure-semantics outcome '" + id
                        + "' — add a reproduction case or declare it as a boundary");
            }
        });
    }

    /** FP-3 — a tool-level failure is a failure on the wire, and records nothing. */
    private void timeoutSurfacesAsFailureNotFakeSuccess() {
        ExecutionOutcome out = engine.execute("probe_upstream", Map.of("mode", "fail"),
                new Principal("alice", "user"));
        assertEquals("error", out.status());
        assertNotNull(out.error(), "the failure reason must reach the caller");
        assertNull(out.effectId(), "a failed call must not record a side effect (no fake success)");
    }

    /** FP-7 — a compensator that cannot be reached is never reported as revoked. */
    private void unavailableIsNeverReportedAsRevoked() {
        SideEffect external = sideEffectRepository.save(new SideEffect(
                "failure-semantics:fp7:" + UUID.randomUUID(), "carol", "sync_crm",
                "crm_record", 42L, "governed_external", "hash-fp7"));

        SideEffect after = sideEffects.revoke(external.getId(), new Principal("carol", "user"));

        assertTrue(!"revoked".equals(after.getRevokeStatus()),
                "an unreachable compensation target must not be reported as revoked");
        assertEquals("compensating", after.getRevokeStatus());
    }

    /** FP-8 — an empty upstream body is carried through as null; nothing is invented. */
    private void emptyUpstreamIsCarriedAsNullNotFabricated() {
        ExecutionOutcome out = engine.execute("probe_upstream", Map.of("mode", "empty"),
                new Principal("alice", "user"));
        assertEquals("executed", out.status());
        assertNull(out.data(), "an empty upstream result stays empty on the wire");
        assertNull(out.effectId(), "a read records no side effect");
    }

    /** FP-1 — same user + tool + args ⇒ the same effect, not a second one. */
    private void repeatedCallReusesTheSameEffect() {
        String args = "{\"note\":\"failure-semantics:fp1:" + UUID.randomUUID() + "\"}";
        Principal alice = new Principal("alice", "user");

        SideEffect first = sideEffects.record(alice, "create_followup", "follow_up", 1L, args, "local_compensate");
        SideEffect second = sideEffects.record(alice, "create_followup", "follow_up", 1L, args, "local_compensate");

        assertEquals(first.getId(), second.getId(), "a repeat call must reuse the existing effect");
    }

    /**
     * FP-5 — a duplicate effect key cannot fork: the DB unique constraint is the backstop, and the
     * loser of a concurrent race resolves to the winner's row instead of surfacing an error.
     */
    private void duplicateEffectKeyCannotFork() {
        String key = "failure-semantics:fp5:" + UUID.randomUUID();
        SideEffect winner = sideEffectRepository.saveAndFlush(
                new SideEffect(key, "alice", "create_followup", "follow_up", 1L, "local_compensate", "hash-fp5"));

        assertThrows(DataIntegrityViolationException.class, () -> sideEffectRepository.saveAndFlush(
                new SideEffect(key, "alice", "create_followup", "follow_up", 2L, "local_compensate", "hash-fp5")));

        // The same race driven through the service: the lookup misses, the insert hits the unique
        // key, and the call is an idempotent skip onto the winner's row — not a failure.
        SideEffectRepository racing = mock(SideEffectRepository.class);
        when(racing.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(racing.saveAndFlush(any(SideEffect.class)))
                .thenThrow(new DataIntegrityViolationException("unique idempotency_key"));
        SideEffectService secondRacer = new SideEffectService(racing, mock(FollowUpRepository.class));

        SideEffect resolved = secondRacer.record(new Principal("alice", "user"), "create_followup",
                "follow_up", 2L, "{}", "local_compensate");
        assertEquals(winner.getId(), resolved.getId(), "the loser must reuse the winner's effect");

        // A conflict that is NOT this key must still fail: the re-read finds nothing, so the original
        // violation stands rather than being swallowed into a fake idempotent hit.
        SideEffectRepository unrelated = mock(SideEffectRepository.class);
        when(unrelated.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(unrelated.saveAndFlush(any(SideEffect.class)))
                .thenThrow(new DataIntegrityViolationException("unique result_id"));
        SideEffectService otherConflict = new SideEffectService(unrelated, mock(FollowUpRepository.class));

        assertThrows(DataIntegrityViolationException.class, () -> otherConflict.record(
                new Principal("alice", "user"), "create_followup", "follow_up", 1L, "{}", "local_compensate"));
    }

    /** FP-4 — a DB error is rethrown, not swallowed into a fake idempotent hit. */
    private void dbErrorPropagatesInsteadOfMaskingAsIdempotentHit() {
        SideEffectRepository broken = mock(SideEffectRepository.class);
        when(broken.findByIdempotencyKey(anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        SideEffectService service = new SideEffectService(broken, mock(FollowUpRepository.class));

        assertThrows(DataAccessResourceFailureException.class, () -> service.record(
                new Principal("alice", "user"), "create_followup", "follow_up", 1L, "{}", "local_compensate"));
        verify(broken, never()).save(any());
    }

    /** FP-2 — a confirmation token is one-shot: a replay is rejected and writes nothing. */
    private void replayedConfirmationTokenIsRejected() {
        Long customerId = customers.save(new Customer("Replay Co", "low", "alice")).getId();
        Principal alice = new Principal("alice", "user");

        ExecutionOutcome pending = engine.execute("create_followup",
                Map.of("customerId", customerId, "note", "fp2"), alice);
        assertEquals("pending_confirmation", pending.status());
        assertEquals("executed", engine.approve(pending.token(), alice).status());

        int written = followUps.findByCustomerIdAndDeletedAtIsNull(customerId).size();
        ResponseStatusException replay = assertThrows(ResponseStatusException.class,
                () -> engine.approve(pending.token(), alice));

        assertEquals(HttpStatus.CONFLICT, replay.getStatusCode(), "a resolved token is not resolvable again");
        assertEquals(written, followUps.findByCustomerIdAndDeletedAtIsNull(customerId).size(),
                "a replayed token must not write a second time");
    }

    /** FP-6 — the audit trail fails closed: a write failure reaches the caller, never a silent drop. */
    private void auditWriteFailurePropagates() {
        AuditLogRepository broken = mock(AuditLogRepository.class);
        when(broken.findTopByOrderByIdDesc()).thenReturn(Optional.empty());
        when(broken.save(any(AuditLog.class)))
                .thenThrow(new DataAccessResourceFailureException("audit store down"));
        // The chain head is there; it is the audit write that fails. That is the point of the case:
        // appends are serialized first, and the failure still reaches the caller.
        AuditChainHeadRepository heads = mock(AuditChainHeadRepository.class);
        when(heads.lockById(AuditChainHead.SINGLETON))
                .thenReturn(Optional.of(new AuditChainHead(AuditChainHead.SINGLETON)));
        AuditService service = new AuditService(broken, heads,
                "abababababababababababababababababababababababababababababababab");

        assertThrows(DataAccessResourceFailureException.class,
                () -> service.append("tool_call", "alice", "probe"));
    }
}
