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
}
