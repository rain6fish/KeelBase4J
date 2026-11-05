// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the record of a failed revocation in a transaction of its own.
 *
 * <p>The attempt runs in {@link SideEffectService#revoke}'s transaction, and the compensator's
 * failure rolls that transaction back — that is what keeps a failed compensation from leaving half a
 * revoke behind. The record of the attempt therefore cannot live in the same transaction: it has to
 * be written while the failed one is being abandoned. {@code REQUIRES_NEW} suspends it, writes the
 * effect's {@code revoke_failed} state, and commits independently.
 *
 * <p>Only a failure <b>after</b> the ownership and revocability checks reaches this class; a
 * refusal before the attempt is not a failed revocation, and a stranger must not be able to stamp a
 * failure onto somebody else's row.
 *
 * <p>记录一次失败撤销，用**它自己的事务**。
 *
 * <p>这次尝试跑在 {@link SideEffectService#revoke} 的事务里，而补偿器的失败会把那个事务回滚 —— 正是它
 * 让一次失败的补偿不留下半截撤销。故「这次尝试发生过」的记录不能住在同一个事务里：它必须在失败事务被放弃的
 * 同时写出去。{@code REQUIRES_NEW} 挂起它，写下 effect 的 {@code revoke_failed}，并独立提交。
 *
 * <p>只有**通过**归属与可撤销检查之后发生的失败才到达本类；尝试之前的拒绝不算一次失败的撤销，而陌生人不该
 * 能往别人的行上盖一个失败。
 */
@Component
public class RevocationTransactions {

    private final SideEffectRepository effects;

    public RevocationTransactions(SideEffectRepository effects) {
        this.effects = effects;
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
}
