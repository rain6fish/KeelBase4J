// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One business operation: who did it, what they did it with, and how it ended.
 *
 * <p>Not hash-chained, and not because that was overlooked: the frozen {@code operation-audit-log-row}
 * marks {@code prevHash} and {@code hash} optional, and chaining a second audit means a second chain
 * head with its own locking. Half of that would be worse than none, so it waits — the column list here
 * is the honest subset, and the object's row in {@code docs/wire-object-endpoints.md} names what is
 * missing and why.
 *
 * 一次业务操作：谁做的、用什么做的、结果如何。
 *
 * <p>**不做哈希链**，而这**不是**疏忽：冻结的 `operation-audit-log-row` 把 `prevHash` 与 `hash` 标为**可选**，
 * 而给第二条审计加链意味着**第二个链头**和它自己的加锁。做一半比不做更糟，所以它先等着 —— 这里的列就是**诚实的子集**，
 * 缺了什么、为什么缺，写在 `docs/wire-object-endpoints.md` 该对象那一行里。
 */
@Entity
@Table(name = "operation_audit_logs")
public class OperationAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for a request that carried no identity, such as a login attempt that was refused. */
    @Column(name = "user_id")
    private String userId;

    /** The frozen vocabulary's own words: LOGIN, LOGOUT, UPLOAD, CREATE, UPDATE, DELETE. */
    @Column(nullable = false, length = 32)
    private String action;

    @Column(nullable = false, length = 16)
    private String method;

    @Column(nullable = false, length = 512)
    private String path;

    /** The row this operation named, read off the request path where the route declares one. */
    @Column(name = "target_id")
    private String targetId;

    @Column(name = "status_code")
    private Integer statusCode;

    /** The direct peer. Behind a proxy this is the proxy unless the deployment forwards the header. */
    @Column(length = 64)
    private String ip;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected OperationAuditLog() {
    }

    public OperationAuditLog(String userId, String action, String method, String path,
                             String targetId, Integer statusCode, String ip, String userAgent) {
        this.userId = userId;
        this.action = action;
        this.method = method;
        this.path = path;
        this.targetId = targetId;
        this.statusCode = statusCode;
        this.ip = ip;
        this.userAgent = userAgent;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getAction() {
        return action;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getTargetId() {
        return targetId;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
