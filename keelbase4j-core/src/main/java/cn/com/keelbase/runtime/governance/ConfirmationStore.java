// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.identity.OperatorIdentity;
import cn.com.keelbase.runtime.identity.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable store of pending confirmations. A token is bound to the operator and may be resolved
 * once; the write happens only on approval.
 */
@Service
public class ConfirmationStore {

    private final ConfirmationRequestRepository repository;

    public ConfirmationStore(ConfirmationRequestRepository repository) {
        this.repository = repository;
    }

    /**
     * Persist a pending confirmation.
     *
     * <p>The initiator's identity is written with the row rather than looked up when it is answered:
     * an approval row outlives the request that raised it, and the write has to run as the person who
     * asked for it — who, by then, is not the caller (ADR-0018). Writing it down also means the write
     * honours the identity the request was raised under, not whatever role that person holds later.
     */
    public ConfirmationRequest create(Principal principal, String toolName, String argsJson,
                                      String riskLevel, String mode) {
        String token = UUID.randomUUID().toString();
        ConfirmationRequest request =
                new ConfirmationRequest(token, toolName, argsJson, principal.userId(), riskLevel);
        request.setMode(mode);
        request.setOperatorIdentity(OperatorIdentity.toWireJson(principal));
        return repository.save(request);
    }

    /**
     * Take a confirmation out of {@code pending} for this operator, atomically — 404 if the token is
     * unknown, 403 if it belongs to somebody else, 409 if it is no longer pending (including when a
     * concurrent decision got there first).
     *
     * <p>The 409 is not a formality: it is the answer the <em>loser</em> of a race gets, and it is
     * what makes "exactly one decider" hold. Whether the row was decided a second earlier or a
     * second later is a distinction the caller cannot act on, so both report the same thing.
     *
     * <p>The returned entity already carries the new status: the conditional update ran in the
     * database, and the copy in hand was read before it. Returning it unchanged would let a later
     * save write the old status back over the claim.
     */
    @Transactional
    public ConfirmationRequest claim(String token, Principal principal, String toStatus) {
        ConfirmationRequest req = repository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown confirmation token"));
        if (!req.getOperatorId().equals(principal.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "confirmation belongs to another operator");
        }
        // An approval row is the operator's own, so ownership does not separate it — the mode does.
        // Without this the conditional update would match nothing and the caller would be told the row
        // is "already pending", which is both true and useless: it is pending, waiting on somebody else.
        if (!ConfirmationMode.IMMEDIATE.equals(req.getMode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "this confirmation is awaiting an approver, not its initiator");
        }
        Instant now = Instant.now();
        int claimed = repository.claim(token, principal.userId(),
                ConfirmationLifecycle.PENDING, toStatus, now, ConfirmationMode.IMMEDIATE);
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "confirmation already " + req.getStatus());
        }
        req.setStatus(toStatus);
        req.setDecidedAt(now);
        return req;
    }

    /** Persist a resolved request (status / decidedAt / resultId). */
    public ConfirmationRequest save(ConfirmationRequest request) {
        return repository.save(request);
    }

    /** The list is capped: the Action Center shows what is recent, not the whole table (ADR-0016). */
    private static final int MAX_ITEMS = 50;

    /**
     * This operator's own confirmation records, newest first, capped — the Action Center's discovery
     * face (ADR-0016).
     *
     * <p>A still-{@code pending} row whose offline window has closed is left out: the window is the one
     * {@link #expireStale} closes, and listing a row that can no longer be decided would offer a button
     * bound to fail. The sweeper moves it to {@code timeout} on its next pass; until then this list
     * already tells the truth about it.
     */
    public List<ConfirmationRequest> mine(String userId, String status, Instant now, long offlineTtlMillis) {
        Instant cutoff = windowCutoff(now, offlineTtlMillis);
        return repository
                .findByOperatorIdAndModeOrderByCreatedAtDesc(userId, ConfirmationMode.IMMEDIATE).stream()
                .filter(row -> status == null || status.equals(row.getStatus()))
                .filter(row -> !windowHasClosed(row, cutoff))
                .limit(MAX_ITEMS)
                .toList();
    }

    /**
     * The cutoff an offline window has reached at {@code now}: a row created before it is past its
     * window. {@link #expireStale} asks the database with this number and {@link #mine} asks the same
     * question in memory with it — one arithmetic, so the two cannot disagree about the same row.
     */
    private static Instant windowCutoff(Instant now, long offlineTtlMillis) {
        return now.minusMillis(offlineTtlMillis);
    }

    private static boolean windowHasClosed(ConfirmationRequest row, Instant cutoff) {
        return ConfirmationLifecycle.PENDING.equals(row.getStatus())
                && row.getCreatedAt().isBefore(cutoff);
    }

    /** What an out-of-band decision did, or why it did nothing (ADR-0015). */
    public enum OutOfBand {
        /** The row moved: the decision is in effect. */
        DECIDED,
        /** Somebody decided it already, or the sweeper timed it out: a no-op, never a second run. */
        ALREADY_DECIDED,
        /** The offline window has closed. Nothing moved; the row is left for the sweeper. */
        EXPIRED,
        /** Unknown token, or one belonging to somebody else — the same answer for both. */
        NOT_FOUND,
        /**
         * A row this path does not serve — an approval row, which waits on a second person even though
         * it is the operator's own. Kept apart from {@link #EXPIRED} because they look alike from the
         * database's side (the conditional update matched nothing) while meaning opposite things: one
         * says the window closed, the other that the window is open and somebody else must answer.
         */
        NOT_THIS_PATHS_ROW
    }

    /** @param request the row as it now stands, or {@code null} when there was no row to speak of */
    public record OutOfBandResult(OutOfBand outcome, ConfirmationRequest request) {
    }

    /**
     * Decide a confirmation from outside the conversation (the operator's own Action Center): only its
     * own operator may, only while it is {@code pending}, and only inside the offline window.
     *
     * <p>An unknown token and somebody else's token answer the same thing on purpose — telling them
     * apart would let a caller probe which tokens exist.
     *
     * <p>Unlike {@link #claim}, nothing here throws: each way this can fail is an outcome the caller
     * reports. In particular "already decided" is a <b>no-op</b> rather than the in-conversation path's
     * conflict, because a second decision outside the conversation is an ordinary thing to attempt (a
     * retry, a second device) — and it must never execute the tool twice.
     *
     * <p>The arbitration is the conditional update — including the window — so of any number of
     * deciders at most one lands.
     */
    @Transactional
    public OutOfBandResult decideOutOfBand(String token, Principal principal, String decision,
                                           Instant now, long offlineTtlMillis) {
        ConfirmationRequest req = repository.findByToken(token).orElse(null);
        if (req == null || !req.getOperatorId().equals(principal.userId())) {
            return new OutOfBandResult(OutOfBand.NOT_FOUND, null);
        }
        if (!ConfirmationMode.IMMEDIATE.equals(req.getMode())) {
            return new OutOfBandResult(OutOfBand.NOT_THIS_PATHS_ROW, req);
        }
        String toStatus = ConfirmationLifecycle.APPROVE.equals(decision)
                ? ConfirmationLifecycle.APPROVED
                : ConfirmationLifecycle.DECLINED;
        int claimed = repository.decideOutOfBand(token, principal.userId(), ConfirmationLifecycle.PENDING,
                toStatus, now, now.minusMillis(offlineTtlMillis), ConfirmationMode.IMMEDIATE);
        if (claimed == 0) {
            // Two very different answers wear the same zero: the row is no longer pending, or its
            // window closed while it still was. Re-read to tell them apart — and a row that is *still*
            // pending can only have been stopped by the window, because the operator check above has
            // already matched and nothing in this application changes a row's operator.
            ConfirmationRequest current = repository.findByToken(token).orElse(null);
            boolean stillPending =
                    current != null && ConfirmationLifecycle.PENDING.equals(current.getStatus());
            return new OutOfBandResult(
                    stillPending ? OutOfBand.EXPIRED : OutOfBand.ALREADY_DECIDED, current);
        }
        req.setStatus(toStatus);
        req.setDecidedAt(now);
        return new OutOfBandResult(OutOfBand.DECIDED, req);
    }

    /** A row by token, or {@code null} — the approval paths look rows up before guarding them. */
    public ConfirmationRequest find(String token) {
        return repository.findByToken(token).orElse(null);
    }

    /**
     * Take a pending <em>approval</em> row for the person answering it (ADR-0018).
     *
     * <p>The two-person rule lives here: an approval is refused when the caller is the row's
     * initiator, because "a second person" is the whole point of the mode. A decline is not refused —
     * withdrawing one's own request is not a self-approval.
     *
     * <p>What makes the claim single is the conditional update, as on the operator's path; the guards
     * above only decide what the caller is told. A row that is not an approval row is reported the
     * same way as one that is no longer pending — both mean "this request cannot move here", and
     * neither changes the row.
     */
    @Transactional
    public ConfirmationRequest claimApproval(String token, Principal approver, String toStatus) {
        ConfirmationRequest req = repository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown confirmation token"));
        if (!ConfirmationMode.APPROVAL.equals(req.getMode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this confirmation is not awaiting an approver");
        }
        if (ConfirmationLifecycle.APPROVED.equals(toStatus) && req.getOperatorId().equals(approver.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "a high-impact action needs a second person: its initiator cannot approve it");
        }
        Instant now = Instant.now();
        int claimed = repository.claimApproval(token, approver.userId(), ConfirmationLifecycle.PENDING,
                toStatus, now, ConfirmationMode.APPROVAL);
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "confirmation already " + req.getStatus());
        }
        req.setStatus(toStatus);
        req.setApproverId(approver.userId());
        req.setDecidedAt(now);
        return req;
    }

    /**
     * Claim one execution attempt on an approved row, and report whether this caller got it.
     *
     * <p>The lease as a conditional update rather than a read-then-write (ADR-0018). It is what makes
     * the retry entry safe: a retry only takes the row when no claim exists or the existing one is
     * older than the lease, so it can never run alongside a live attempt.
     *
     * <p>Transactional because the statement is an update, and <b>the caller must write {@code at}
     * back onto its own copy</b>: the update ran in the database and the copy in hand was read before
     * it, so a later save of that copy would wipe the claim it just took (the same trap
     * {@link #claim} documents).
     */
    @Transactional
    public boolean claimExecution(String token, Instant at, long leaseMillis) {
        return repository.claimExecution(token, ConfirmationLifecycle.APPROVED, at,
                at.minusMillis(leaseMillis)) > 0;
    }

    /**
     * Close the offline window on every confirmation whose time is up, and report how many were
     * closed.
     *
     * <p>This is the {@code offline_ttl_elapsed} transition of the frozen lifecycle, and it is the
     * only thing that ends a confirmation nobody decided: the in-conversation wait expiring leaves
     * the row {@code pending} on purpose, so without a sweep a confirmation would stay actionable
     * forever. A deployment that wants the window configurable passes its own value; the default is
     * the contract's.
     */
    @Transactional
    public int expireStale(Instant now, long offlineTtlMillis) {
        return repository.expireStale(
                ConfirmationLifecycle.PENDING,
                ConfirmationLifecycle.TIMEOUT,
                windowCutoff(now, offlineTtlMillis),
                now);
    }
}
