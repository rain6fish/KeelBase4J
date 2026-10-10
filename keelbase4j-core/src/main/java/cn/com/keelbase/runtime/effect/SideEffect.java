// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A recorded AI write side effect — the unit of idempotency and revocation. */
@Entity
@Table(name = "side_effects")
public class SideEffect {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Content-derived key: same user + tool + args ⇒ same effect (no duplicate writes). */
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "result_type", nullable = false)
    private String resultType;

    @Column(name = "result_id", nullable = false)
    private Long resultId;

    /** none | local_compensate | governed_external. */
    @Column(name = "revoke_class", nullable = false)
    private String revokeClass;

    /** executed | revoked | compensating. */
    @Column(name = "revoke_status", nullable = false)
    private String revokeStatus = "executed";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * Hash of the call's arguments — the console shows it and uses it to tell one effect from another.
     *
     * <p>Distinct from {@link #idempotencyKey} on purpose: that one folds in the user and the tool as
     * well, so publishing it as "the arguments' hash" would misdescribe it. This hashes the arguments
     * and nothing else.
     */
    @Column(name = "args_hash", nullable = false)
    private String argsHash;

    /**
     * What the tool produced, as JSON, captured when the write ran.
     *
     * <p>The frozen {@code traceItem} calls this {@code afterSnapshot}, and it is stored rather than
     * re-derived at read time for the reason the name gives: the row as it stands today is not the row
     * the decision produced. There is no `beforeSnapshot` beside it — nothing in this runtime
     * overwrites an existing row, so there is no before to record (see {@code V5__effect_snapshot}).
     *
     * 工具**产出了什么**，以 JSON 记下、在写跑成的那一刻捕获。
     *
     * <p>冻结的 {@code traceItem} 管它叫 {@code afterSnapshot}；它**被存下来**、而不是读时重推，理由就在
     * 这个名字里：**今天这一行**的样子**不是这次决策产出的样子**。旁边没有 `beforeSnapshot` ——
     * 本运行时**不改写既有行**，所以没有「之前」可记（见 `V5__effect_snapshot`）。
     */
    @Column(name = "after_snapshot")
    private String afterSnapshot;

    protected SideEffect() {
    }

    public SideEffect(String idempotencyKey, String userId, String toolName, String resultType,
                      Long resultId, String revokeClass, String argsHash, String afterSnapshot) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.toolName = toolName;
        this.resultType = resultType;
        this.resultId = resultId;
        this.revokeClass = revokeClass;
        this.argsHash = argsHash;
        this.afterSnapshot = afterSnapshot;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getResultType() {
        return resultType;
    }

    public Long getResultId() {
        return resultId;
    }

    public String getRevokeClass() {
        return revokeClass;
    }

    public String getRevokeStatus() {
        return revokeStatus;
    }

    public String getArgsHash() {
        return argsHash;
    }

    public String getAfterSnapshot() {
        return afterSnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setRevokeStatus(String revokeStatus) {
        this.revokeStatus = revokeStatus;
    }
}
