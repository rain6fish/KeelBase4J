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

    /** Resolve a token; 404 if unknown, 403 if it belongs to another operator. */
    public ConfirmationRequest requireOwnedPending(String token, Principal principal) {
        ConfirmationRequest req = repository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown confirmation token"));
        if (!req.getOperatorId().equals(principal.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "confirmation belongs to another operator");
        }
        if (!ConfirmationLifecycle.PENDING.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "confirmation already " + req.getStatus());
        }
        return req;
    }

    /** Persist a resolved request (status / decidedAt / resultId). */
    public ConfirmationRequest save(ConfirmationRequest request) {
        return repository.save(request);
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
