// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import cn.com.keelbase.runtime.domain.FollowUp;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.identity.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Records and revokes AI write side effects.
 *
 * <p>Idempotency is content-derived (user + tool + args), so re-running the same call reuses the
 * existing effect instead of writing twice. A <em>concurrent</em> duplicate — two calls that both
 * miss the lookup — is resolved the same way: the unique key rejects the loser, which then re-reads
 * and returns the row the winner wrote. That is the frozen {@code unique_conflict_idempotent}
 * disposition: skip, never fork, and never surface a spurious failure. Only a conflict on this key
 * is treated that way; any other integrity failure is still a real failure.
 *
 * <p>Revocation is class-aware: this spike only has local
 * entities, so revoke means a local soft delete ({@code local_compensate}) — never a claim of
 * "reverted" for something that cannot be.
 */
@Service
public class SideEffectService {

    private final SideEffectRepository repository;
    private final FollowUpRepository followUps;

    public SideEffectService(SideEffectRepository repository, FollowUpRepository followUps) {
        this.repository = repository;
        this.followUps = followUps;
    }

    /**
     * Record the effect for this call, or reuse the one this content already produced.
     *
     * <p>Deliberately not {@code @Transactional}: the insert must be able to fail and roll back
     * <em>on its own</em>, so the retry below reads from a fresh persistence context. Wrapping both
     * in one transaction would poison it on the first conflict (the persistence context is unusable
     * after a constraint violation), and would keep working on H2 only by accident.
     */
    public SideEffect record(Principal principal, String toolName, String resultType, Long resultId,
                             String argsJson, String revokeClass) {
        String key = idempotencyKey(principal.userId(), toolName, argsJson);
        Optional<SideEffect> existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return repository.saveAndFlush(
                    new SideEffect(key, principal.userId(), toolName, resultType, resultId, revokeClass));
        } catch (DataIntegrityViolationException race) {
            // A concurrent call with the same key won. The effect exists, so this call is a skip, not
            // a failure — return the winner's row. If the re-read finds nothing the violation was
            // something other than this key, and the original failure stands.
            return repository.findByIdempotencyKey(key).orElseThrow(() -> race);
        }
    }

    @Transactional
    public SideEffect revoke(Long effectId, Principal principal) {
        SideEffect effect = repository.findById(effectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown side effect"));
        if (!principal.isManager() && !principal.userId().equals(effect.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your side effect");
        }
        if ("none".equals(effect.getRevokeClass())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "effect is not revocable");
        }
        if ("revoked".equals(effect.getRevokeStatus())) {
            return effect; // idempotent revoke
        }
        if ("local_compensate".equals(effect.getRevokeClass())) {
            softDeleteTarget(effect);
            effect.setRevokeStatus("revoked");
        } else {
            // governed_external etc. are out of scope for G1 — honest "compensating", never "revoked".
            effect.setRevokeStatus("compensating");
        }
        return repository.save(effect);
    }

    private void softDeleteTarget(SideEffect effect) {
        if (!"follow_up".equals(effect.getResultType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "no local revoke path for resultType=" + effect.getResultType());
        }
        FollowUp target = followUps.findById(effect.getResultId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "target not found"));
        target.setDeletedAt(Instant.now());
        followUps.save(target);
    }

    static String idempotencyKey(String userId, String toolName, String argsJson) {
        return sha256Hex(userId + ":" + toolName + ":" + argsJson);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
