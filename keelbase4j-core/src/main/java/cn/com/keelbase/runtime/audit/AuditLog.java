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

    protected AuditLog() {
    }

    public AuditLog(String action, String userId, String detail, String prevHash, String hash,
                    boolean error) {
        this.action = action;
        this.userId = userId;
        this.detail = detail;
        this.prevHash = prevHash;
        this.hash = hash;
        this.error = error;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
