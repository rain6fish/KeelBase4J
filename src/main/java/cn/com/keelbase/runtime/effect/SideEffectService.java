// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import cn.com.keelbase.runtime.domain.FollowUp;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.identity.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Records and revokes AI write side effects.
 *
 * <p>Idempotency is content-derived (user + tool + args), so re-running the same call reuses the
 * existing effect instead of writing twice. Revocation is class-aware: this spike only has local
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

    @Transactional
    public SideEffect record(Principal principal, String toolName, String resultType, Long resultId,
                             String argsJson, String revokeClass) {
        String key = idempotencyKey(principal.userId(), toolName, argsJson);
        return repository.findByIdempotencyKey(key)
                .orElseGet(() -> repository.save(
                        new SideEffect(key, principal.userId(), toolName, resultType, resultId, revokeClass)));
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
