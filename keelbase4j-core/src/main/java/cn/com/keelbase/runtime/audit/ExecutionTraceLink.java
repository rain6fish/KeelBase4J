// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One governed attempt, paired with the host's trace id.
 *
 * <p>The link is stored beside both records, not inside either: the audit chain keeps its frozen
 * payload, and the host keeps its own operation log. One row per {@code executionId} is the whole
 * table — a replay of the same attempt finds the row already there rather than adding a second.
 *
 * 一次受治理尝试，与宿主的 trace id 配对。
 *
 * <p>这条关联存在**两份记录之外**，不进入任何一份：审计链保持它冻结的载荷，宿主保留自己的操作日志。
 * 每个 `executionId` 一行就是整张表 —— 同一次尝试再来一次，读到的是已有行，而不是多写一行。
 */
@Entity
@Table(name = "execution_trace_links")
public class ExecutionTraceLink {

    @Id
    @Column(name = "execution_id", length = 64)
    private String executionId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(length = 255)
    private String tool;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ExecutionTraceLink() {
    }

    public ExecutionTraceLink(String executionId, String traceId, String tool, String userId,
                              String conversationId) {
        this.executionId = executionId;
        this.traceId = traceId;
        this.tool = tool;
        this.userId = userId;
        this.conversationId = conversationId;
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getTool() {
        return tool;
    }

    public String getUserId() {
        return userId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
