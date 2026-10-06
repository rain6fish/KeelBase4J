// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import cn.com.keelbase.runtime.audit.AuditService;
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
 *
 * <p><b>A revocation is audited, and that is not decoration.</b> It used to write the ledger row and
 * the soft delete and nothing else, so the chain went on describing a write and never that somebody
 * undid it — and "who undid what" is the one question the operation exists to answer. Every other
 * governance transition already writes a line; this one was an omission. The row carries the acting
 * user, so the trace answers it without a second table.
 *
 * <p>记录与撤销 AI 的写副作用。
 *
 * <p>幂等由内容推导（user + tool + args），故重复同一次调用会**复用**已有 effect、而不是写两次。**并发**
 * 重复——两次调用都没查到时——用同一条路解决：唯一键拒绝输家，输家再读回赢家写的行、把它返回。这就是冻结的
 * `unique_conflict_idempotent` 处置：**跳过、绝不分裂、绝不冒出假失败**。只有这个键上的冲突才这样处理；
 * 其它完整性失败仍然是真失败。
 *
 * <p>撤销是**分档**的：这个 spike 只有本地实体，所以撤销 = 本地软删（`local_compensate`）——绝不为做不到
 * 的事情宣称「已还原」。
 *
 * <p>**撤销是要进审计的，这不是装饰。** 它过去只写账本行与软删、别的什么都不写，于是链继续描述着一次写、
 * **从不描述有人把它撤了**——而「谁撤了什么」正是这个操作存在的唯一理由。其它每一次治理状态迁移都写了行；
 * 这一处是**遗漏**。行里带着执行者，所以轨迹不需要第二张表就能回答它。
 */
@Service
public class SideEffectService {

    private final SideEffectRepository repository;
    private final FollowUpRepository followUps;
    private final AuditService audit;

    public SideEffectService(SideEffectRepository repository, FollowUpRepository followUps,
                            AuditService audit) {
        this.repository = repository;
        this.followUps = followUps;
        this.audit = audit;
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
            return repository.saveAndFlush(new SideEffect(
                    key, principal.userId(), toolName, resultType, resultId, revokeClass,
                    argsHash(argsJson)));
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
        SideEffect saved = repository.save(effect);
        // The actor, not the effect's owner: an operator revoking somebody else's effect is exactly
        // the case the trace has to be able to name. Written after the row moves, so a revoke that
        // throws on the way (an unreachable target, say) leaves no line claiming it happened.
        //
        // The action is the contract's own `effect_revoke`, not `tool_call`: v2 added that value to
        // the frozen vocabulary for this exact act — a revocation of a real AI side effect leaves a
        // line, where before the status moved with no AI audit trail at all (the escape this closed).
        // Writing `tool_call` for it kept the row inside the vocabulary but flattened a revocation
        // into an ordinary call, which is what a reader of the audit — or a rate computed from it —
        // could no longer tell apart.
        //
        // 动作取契约自己的 `effect_revoke`、不是 `tool_call`：v2 正是**为了这一动作**把该值加进冻结词表
        // ——对**真实** AI 副作用的撤销要留一行，而在此之前状态变了却没有 AI 审计留痕（正是它堵上的那个
        // 逃逸口）。写成 `tool_call` 虽然仍在词表内，却把一次撤销抹平成了普通调用——读审计的人、以及从审计
        // 里算出来的率，从此分不出这一行是什么。
        audit.append("effect_revoke", principal.userId(),
                saved.getToolName() + " revoked (effect " + saved.getId() + ", "
                        + saved.getRevokeClass() + " -> " + saved.getRevokeStatus() + ")");
        return saved;
    }

    /**
     * The effect this content already produced, if any — the same key {@link #record} would compute,
     * asked <em>before</em> a tool runs rather than after. See
     * {@code GovernedExecutionEngine#reuseIfAlreadyExecuted} for why the asking has to come first: the
     * key describes a write only if there can be at most one write under it, and a probe after the
     * fact cannot make that true.
     *
     * <p>Returns the row whatever its revoke status — the caller decides what a revoked key means. A
     * probe that filtered revoked rows out would report "this call is new" about a call that is not.
     *
     * <p>某个内容的 effect 是否已经存在——与 {@link #record} 会算出的**同一个键**，但问的时机是**工具执行
     * 之前**而非之后。为何必须先问，见 `GovernedExecutionEngine#reuseIfAlreadyExecuted`：**只有当同一个键下
     * 至多只有一次写时，这个键才真的在描述一次写**，而事后探测做不到这一点。
     *
     * <p>无论撤销状态如何都返回该行——**撤销过的键意味着什么，由调用方决定**。把已撤销的行过滤掉，会让探测
     * 对一次并非新调用的事情报告「这是新调用」。
     */
    public Optional<SideEffect> existingFor(Principal principal, String toolName, String argsJson) {
        return repository.findByIdempotencyKey(idempotencyKey(principal.userId(), toolName, argsJson));
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

    /** The arguments' own hash — what the console reads as {@code argsHash}. See {@link SideEffect}. */
    static String argsHash(String argsJson) {
        return sha256Hex(argsJson);
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
