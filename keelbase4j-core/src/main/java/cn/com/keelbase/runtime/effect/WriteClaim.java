// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The row that arbitrates a write <em>before</em> it happens, so two identical calls cannot both perform it.
 *
 * <p>The ledger's idempotency key is content-derived and the effect row is written <em>after</em> the
 * tool runs. That is enough to keep the ledger from recording the same call twice, and — since
 * {@code GovernedExecutionEngine} asks the ledger before executing — enough to stop a sequential repeat
 * from writing at all. It is not enough for two calls that arrive <em>at the same instant</em>: both ask
 * the ledger, both find nothing, both write. The effect row's unique key then resolves the duplicate
 * <em>record</em>, which is the one thing that does not need resolving — the second write has already
 * happened, and the row it wrote is named by no effect and cannot be revoked.
 *
 * <p>So the arbitration moves to where the write is: a unique key taken before execution. Whoever
 * inserts it owns the execution; everyone else is told not to execute. This is the reference
 * implementation's shape (a write claim with {@code claimed}/{@code settled}/{@code released}), kept
 * deliberately small: the fields that decide anything are the key and the status.
 *
 * <p><b>The status set is the whole protocol.</b> {@code claimed} means an execution is in flight — or
 * that a previous one died without saying otherwise, and the two are <em>not distinguishable</em>, which
 * is why neither is retried. {@code released} means a previous attempt failed in a way that proves
 * nothing landed, so a retry may re-claim (conditionally — still arbitration, not an unconditional
 * overwrite). {@code settled} means the effect it produced is recorded, and {@code effectId} says which.
 *
 * <p>它在写**发生之前**仲裁，使两次相同调用不可能都执行。
 *
 * <p>账本的幂等键由内容推导，而 effect 行是工具跑完**之后**才写的。这足以让账本不把同一次调用记两遍，也——因为
 * `GovernedExecutionEngine` 会**执行前**问账本——足以让**顺序**重复不再写。但对**同一瞬间**到达的两次调用不够：
 * 两边都问账本、都没查到、都写。effect 行的唯一键随后裁决的是**记录**这一半，而那一半恰恰不需要裁决——第二次写
 * 已经发生，它写下的那行**没有任何 effect 指向它、也撤不掉**。
 *
 * <p>于是仲裁挪到写发生的地方：**执行前**取一个唯一键。插进去的那个人拥有这次执行，其余人被告知不要执行。这是
 * 参照实现的形状（带 `claimed`/`settled`/`released` 的 write claim），但有意做小：真正决定事情的字段只有键与状态。
 *
 * <p>**状态集本身就是全部协议。** `claimed` = 有一次执行在飞——或者上一次执行死了却没留下别的话，而这两者
 * **无法区分**，所以两者都不重试。`released` = 上一次尝试以一种「证明什么都没落地」的方式失败，故重试可以**条件
 * 地**重新占位（仍是仲裁，不是无条件覆盖）。`settled` = 它产出的 effect 已登记，`effectId` 指出是哪一个。
 */
@Entity
@Table(name = "write_claims")
public class WriteClaim {

    /** An execution is in flight, or died without releasing — indistinguishable, so never re-run. */
    public static final String CLAIMED = "claimed";

    /** The effect this execution produced is recorded; {@link #effectId} names it. */
    public static final String SETTLED = "settled";

    /** The attempt failed in a way that proves nothing landed, so a retry may re-claim. */
    public static final String RELEASED = "released";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The same content-derived key the effect uses — see {@code SideEffectService#idempotencyKey}. */
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(nullable = false)
    private String status = CLAIMED;

    @Column(name = "effect_id")
    private Long effectId;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt = Instant.now();

    @Column(name = "settled_at")
    private Instant settledAt;

    protected WriteClaim() {
    }

    public WriteClaim(String idempotencyKey, String userId, String toolName) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.toolName = toolName;
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getUserId() {
        return userId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getStatus() {
        return status;
    }

    public Long getEffectId() {
        return effectId;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }
}
