// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One AI-audit record, hash-chained to its predecessor. */
@Entity
@Table(name = "ai_audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String action;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false, length = 2000)
    private String detail;

    @Column(name = "prev_hash")
    private String prevHash;

    @Column(nullable = false)
    private String hash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * Whether this row records a call that did not go the way the caller asked — a refusal, a block, a
     * decline, or an execution that failed. The frozen {@code audit-chain-verification} asks for it on
     * every row it lists; see {@code V6__audit_row_is_error} for why it is **not** part of the hashed
     * payload.
     *
     * 这一行是否记着一次**没按调用方所求发生**的调用 —— 拒绝、拦截、否决、或执行失败。冻结的
     * {@code audit-chain-verification} 对它列出的每一行都要这个值；它**为什么不在哈希载荷里**，见
     * `V6__audit_row_is_error`。
     */
    @Column(name = "is_error", nullable = false)
    private boolean error;

    /**
     * What this proposal came to, as a frozen vocabulary rather than as prose — C-3. Which values are
     * legal for which {@code action} belongs to the contract ({@code ai-audit-log-outcome-v1-vector.json}),
     * not to this class. **Not part of the hashed payload** — see {@code V7__audit_row_outcome_and_tool}.
     *
     * 这次提议的**结局** —— 用冻结词表说，而不是用散文说（C-3）。哪些值对哪种 {@code action} 合法
     * **归契约管**（`ai-audit-log-outcome-v1-vector.json`），不归本类管。**不进哈希载荷** —— 见
     * `V7__audit_row_outcome_and_tool`。
     */
    @Column(length = 32)
    private String outcome;

    /**
     * The tool this row is about, or null when the row is not about one — a non-tool row, or a decision
     * covering several. Not part of the hashed payload.
     *
     * 本行所属的**工具名**；**不是关于某一个工具的行**为 null —— 非工具行，或一次覆盖多个的裁决。
     * 不进哈希载荷。
     */
    @Column(length = 64)
    private String tool;

    /**
     * The conversation this row belongs to, or null when it belongs to none — a task run, an MCP
     * caller, or a row written before the column existed (see {@code V11__conversation_link}). Not
     * part of the hashed payload, so adding it beside the chain leaves every existing chain
     * verifiable.
     *
     * 本行所属的**会话**；**不属于任何会话**时为 null —— 一次任务运行、一个 MCP 调用方，或在本列存在之前写下的行
     * （见 `V11__conversation_link`）。不进哈希载荷，故把它加在链**旁边**不破坏任何既有链。
     */
    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    protected AuditLog() {
    }

    /**
     * Kept as the three-argument callers know it; the two C-3 columns stay null. The published artifact
     * carries this signature, so it is added to rather than replaced.
     *
     * 保持三参调用方认识的样子；C-3 的两列留 null。已发布的 artifact 带着这个签名，故**加**而不**改**。
     */
    public AuditLog(String action, String userId, String detail, String prevHash, String hash,
                    boolean error) {
        this(action, userId, detail, prevHash, hash, error, null, null);
    }

    /**
     * The same, without a conversation — kept for the callers that have none to pass, and for the
     * published artifact's sake. See the roomier constructor below.
     *
     * 同上、但不带会话 —— 为没有会话可传的调用方、也为已发布的 artifact 而保留。见下面那个更宽的构造器。
     */
    public AuditLog(String action, String userId, String detail, String prevHash, String hash,
                    boolean error, String outcome, String tool) {
        this(action, userId, detail, prevHash, hash, error, outcome, tool, null);
    }

    public AuditLog(String action, String userId, String detail, String prevHash, String hash,
                    boolean error, String outcome, String tool, String conversationId) {
        this.action = action;
        this.userId = userId;
        this.detail = detail;
        this.prevHash = prevHash;
        this.hash = hash;
        this.error = error;
        this.outcome = outcome;
        this.tool = tool;
        this.conversationId = conversationId;
    }

    public Long getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public String getUserId() {
        return userId;
    }

    public boolean isError() {
        return error;
    }

    public String getDetail() {
        return detail;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getHash() {
        return hash;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getTool() {
        return tool;
    }

    public String getConversationId() {
        return conversationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
