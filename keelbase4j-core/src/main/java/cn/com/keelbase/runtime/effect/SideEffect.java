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
     * <p><b>⚠ Corrected 2026-10-15:</b> that sentence stopped being true when a tool that
     * <em>updates</em> a row arrived (the host's {@code update_crm_customer}), and {@link #beforeSnapshot}
     * now stands beside this field for exactly that case — see {@code V12__effect_before_snapshot}. The
     * sentence is kept as written so the correction has something to point at.
     *
     * 工具**产出了什么**，以 JSON 记下、在写跑成的那一刻捕获。
     *
     * <p>冻结的 {@code traceItem} 管它叫 {@code afterSnapshot}；它**被存下来**、而不是读时重推，理由就在
     * 这个名字里：**今天这一行**的样子**不是这次决策产出的样子**。旁边没有 `beforeSnapshot` ——
     * 本运行时**不改写既有行**，所以没有「之前」可记（见 `V5__effect_snapshot`）。
     *
     * <p><b>⚠ 2026-10-15 更正：</b>那句在一个会**改行**的工具到来后（宿主的 `update_crm_customer`）不再成立，
     * 而 {@link #beforeSnapshot} 如今正是为那件事立在这一字段旁边 —— 见 `V12__effect_before_snapshot`。
     * 原句保留，好让这条更正有东西可指。
     */
    @Column(name = "after_snapshot")
    private String afterSnapshot;

    /**
     * The row as it was before this call changed it, or null when the call created one.
     *
     * <p>The frozen {@code traceItem} calls this {@code beforeSnapshot}. It is supplied by the tool
     * rather than read by the runtime: the runtime does not know which host table a result type lives
     * in, and a second read of a row the tool has already read would be a second call to a business
     * service that never asked for one. Null is the honest answer for a create — there was no row.
     *
     * 这一行在被这次调用改**之前**的样子；这次调用是**新建**时为 null。
     *
     * <p>冻结的 {@code traceItem} 管它叫 {@code beforeSnapshot}。它由**工具**提供、不由运行时读取：运行时不知道
     * 一个结果类型住在宿主的哪张表里，而对工具**已经读过**的那一行再读一次，等于对一个从未要求过的业务服务
     * 再发一次调用。**新建**时 null 是诚实的答案 —— 那时没有行。
     */
    @Column(name = "before_snapshot")
    private String beforeSnapshot;

    /**
     * The conversation whose call produced this effect, or null when the call belonged to none — a
     * task run, an MCP caller, or an effect recorded before the column existed (see
     * {@code V11__conversation_link}). It is what lets a decision trace show the write a
     * conversation proposed.
     *
     * 产出这一副作用的那次调用所属的**会话**；那次调用不属于任何会话时为 null —— 一次任务运行、一个 MCP 调用方，
     * 或在列存在之前记下的副作用（见 `V11__conversation_link`）。正是它让决策轨迹显示得出某条会话提议的那次写。
     */
    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    protected SideEffect() {
    }

    /**
     * The same, without a conversation — kept for the callers that have none to pass, and for the
     * published artifact's sake. See the roomier constructor below.
     *
     * 同上、但不带会话 —— 为没有会话可传的调用方、也为已发布的 artifact 而保留。见下面那个更宽的构造器。
     */
    public SideEffect(String idempotencyKey, String userId, String toolName, String resultType,
                      Long resultId, String revokeClass, String argsHash, String afterSnapshot) {
        this(idempotencyKey, userId, toolName, resultType, resultId, revokeClass, argsHash, null,
                afterSnapshot, null);
    }

    public SideEffect(String idempotencyKey, String userId, String toolName, String resultType,
                      Long resultId, String revokeClass, String argsHash, String afterSnapshot,
                      String conversationId) {
        this(idempotencyKey, userId, toolName, resultType, resultId, revokeClass, argsHash, null,
                afterSnapshot, conversationId);
    }

    /**
     * The same, plus the row as it was before this call changed it — the frozen {@code traceItem}'s
     * {@code beforeSnapshot}.
     *
     * <p>An overload rather than a change to the one above it, for the reason this class already gives
     * about its shorter calls: this class lives in a published artifact, and a signature that gains a
     * parameter breaks a caller with none to pass. A create passes nothing and gets null, which is what
     * "there was no row before" honestly is.
     *
     * 同上，另加这一行在被这次调用改**之前**的样子 —— 冻结 `traceItem` 的 `beforeSnapshot`。
     *
     * <p>做成**重载**、而不去改上面那个，理由本类对它的短调用已经写过：这个类住在一个**已发布**的 artifact 里，
     * 而签名多一个参数会让手上没有这个值的调用方编译不过。**新建**不传、得到 null —— 那正是「之前没有行」。
     */
    public SideEffect(String idempotencyKey, String userId, String toolName, String resultType,
                      Long resultId, String revokeClass, String argsHash, String beforeSnapshot,
                      String afterSnapshot, String conversationId) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.toolName = toolName;
        this.resultType = resultType;
        this.resultId = resultId;
        this.revokeClass = revokeClass;
        this.argsHash = argsHash;
        this.beforeSnapshot = beforeSnapshot;
        this.afterSnapshot = afterSnapshot;
        this.conversationId = conversationId;
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

    public String getBeforeSnapshot() {
        return beforeSnapshot;
    }

    public String getConversationId() {
        return conversationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setRevokeStatus(String revokeStatus) {
        this.revokeStatus = revokeStatus;
    }
}
