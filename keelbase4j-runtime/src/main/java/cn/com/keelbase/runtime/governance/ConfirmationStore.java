// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.identity.Principal;
import java.time.Instant;
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

    public ConfirmationRequest create(Principal principal, String toolName, String argsJson, String riskLevel) {
        String token = UUID.randomUUID().toString();
        return repository.save(new ConfirmationRequest(token, toolName, argsJson, principal.userId(), riskLevel));
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
        Instant now = Instant.now();
        int claimed = repository.claim(token, principal.userId(),
                ConfirmationLifecycle.PENDING, toStatus, now);
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

    /** What an out-of-band decision did, or why it did nothing (ADR-0015). */
    public enum OutOfBand {
        /** The row moved: the decision is in effect. */
        DECIDED,
        /** Somebody decided it already, or the sweeper timed it out: a no-op, never a second run. */
        ALREADY_DECIDED,
        /** The offline window has closed. Nothing moved; the row is left for the sweeper. */
        EXPIRED,
        /** Unknown token, or one belonging to somebody else — the same answer for both. */
        NOT_FOUND
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
        String toStatus = ConfirmationLifecycle.APPROVE.equals(decision)
                ? ConfirmationLifecycle.APPROVED
                : ConfirmationLifecycle.DECLINED;
        int claimed = repository.decideOutOfBand(token, principal.userId(), ConfirmationLifecycle.PENDING,
                toStatus, now, now.minusMillis(offlineTtlMillis));
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
                now.minusMillis(offlineTtlMillis),
                now);
    }
}
