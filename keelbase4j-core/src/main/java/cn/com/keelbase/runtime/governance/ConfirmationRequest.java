// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A durable confirmation request: the write does not happen until this is approved. */
@Entity
@Table(name = "confirmation_requests")
public class ConfirmationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(nullable = false, length = 4000)
    private String argsJson;

    @Column(name = "operator_id", nullable = false)
    private String operatorId;

    @Column(name = "risk_level", nullable = false)
    private String riskLevel;

    /** pending | approved | declined | timeout — see {@link ConfirmationLifecycle}. */
    @Column(nullable = false)
    private String status = ConfirmationLifecycle.PENDING;

    @Column(name = "result_id")
    private Long resultId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * The execution axis (ADR-0016, the frozen v2's): when an execution attempt took the row, when one
     * succeeded, and why the last one failed. Separate from {@code status} on purpose — a row can be
     * {@code approved} and still be running, and the lifecycle's state set does not gain a value for it.
     *
     * <p>{@code executionClaimedAt} doubles as the lease: an attempt younger than
     * {@link ExecutionAxis#LEASE_MILLIS} is reported as running, an older one as failed.
     */
    @Column(name = "execution_claimed_at")
    private Instant executionClaimedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "execution_error", length = 1000)
    private String executionError;

    protected ConfirmationRequest() {
    }

    public ConfirmationRequest(String token, String toolName, String argsJson, String operatorId, String riskLevel) {
        this.token = token;
        this.toolName = toolName;
        this.argsJson = argsJson;
        this.operatorId = operatorId;
        this.riskLevel = riskLevel;
    }

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArgsJson() {
        return argsJson;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getResultId() {
        return resultId;
    }

    public void setResultId(Long resultId) {
        this.resultId = resultId;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExecutionClaimedAt() {
        return executionClaimedAt;
    }

    public void setExecutionClaimedAt(Instant executionClaimedAt) {
        this.executionClaimedAt = executionClaimedAt;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(Instant executedAt) {
        this.executedAt = executedAt;
    }

    public String getExecutionError() {
        return executionError;
    }

    public void setExecutionError(String executionError) {
        this.executionError = executionError;
    }
}
