// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import cn.com.keelbase.runtime.audit.AuditService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the record of a failed revocation in a transaction of its own.
 *
 * <p>The attempt runs in {@link SideEffectService#revoke}'s transaction, and the compensator's
 * failure rolls that transaction back — that is what keeps a failed compensation from leaving half a
 * revoke behind. The records of the attempt therefore cannot live in the same transaction: they have
 * to be written while the failed one is being abandoned. {@code REQUIRES_NEW} suspends it, writes
 * the effect's {@code revoke_failed} state and the chain's {@code effect_revoke + failed} row, and
 * commits each independently.
 *
 * <p>Only a failure <b>after</b> the ownership and revocability checks reaches this class; a
 * refusal before the attempt is not a failed revocation, and a stranger must not be able to stamp a
 * failure onto somebody else's row.
 *
 * <p>记录一次失败撤销，用**它自己的事务**。
 *
 * <p>这次尝试跑在 {@link SideEffectService#revoke} 的事务里，而补偿器的失败会把那个事务回滚 —— 正是它
 * 让一次失败的补偿不留下半截撤销。故「这次尝试发生过」的记录不能住在同一个事务里：它们必须在失败事务被放弃的
 * 同时写出去。{@code REQUIRES_NEW} 挂起它，写下 effect 的 {@code revoke_failed}、以及链上那行
 * {@code effect_revoke + failed}，并各自独立提交。
 *
 * <p>只有**通过**归属与可撤销检查之后发生的失败才到达本类；尝试之前的拒绝不算一次失败的撤销，而陌生人不该
 * 能往别人的行上盖一个失败。
 */
@Component
public class RevocationTransactions {

    private final SideEffectRepository effects;
    private final AuditService audit;

    public RevocationTransactions(SideEffectRepository effects, AuditService audit) {
        this.effects = effects;
        this.audit = audit;
    }

    /**
     * Marks the effect as the target of a failed attempt. A concurrent success wins: if the row is
     * already {@code revoked}, this leaves it alone rather than overwriting a fact with a miss.
     *
     * 把这个 effect 标成一次失败尝试的目标。并发的成功优先：行已经是 {@code revoked} 时保持原样，
     * 而不是拿一次失手去覆盖一个已经发生的事实。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long effectId) {
        effects.findById(effectId).ifPresent(effect -> {
            if (!"revoked".equals(effect.getRevokeStatus())) {
                effect.setRevokeStatus("revoke_failed");
                effects.save(effect);
            }
        });
    }

    /**
     * Puts the failed attempt on the audit chain, in a transaction of its own. The row keeps the
     * contract's revocation action and says how the attempt ended: {@code outcome=failed},
     * {@code isError=true}. A successful revoke writes {@code outcome=revoked}, so the chain reads
     * the two endings apart, and "never attempted" writes nothing at all.
     *
     * <p>Separate from {@link #markFailed}: one failing must not cost the other. If this append
     * fails, the effect still carries the failed state; the caller logs and rethrows the compensation
     * failure, which is the fact the operator has to see.
     *
     * 把这次失败尝试写到审计链上，用**它自己的事务**。这一行保留契约的撤销动作，并写明这次尝试怎么了：
     * {@code outcome=failed}、{@code isError=true}。成功的撤销写 {@code outcome=revoked}，故链上读得出
     * 两种结局；「从未尝试」什么也不写。
     *
     * <p>与 {@link #markFailed} **分开**：一个失败不该让另一个也丢。若这次追加失败，effect 仍带着失败状态；
     * 调用方记日志并抛回补偿失败 —— 那才是运维必须看到的事实。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendFailed(Long effectId, String actor, String reason) {
        effects.findById(effectId).ifPresentOrElse(
                effect -> audit.append("effect_revoke", actor,
                        effect.getToolName() + " revoke failed (effect " + effectId + ", "
                                + effect.getRevokeClass() + "): " + reason,
                        true, "failed", effect.getToolName()),
                () -> audit.append("effect_revoke", actor,
                        "revoke failed (effect " + effectId + "): " + reason,
                        true, "failed", null));
    }
}
