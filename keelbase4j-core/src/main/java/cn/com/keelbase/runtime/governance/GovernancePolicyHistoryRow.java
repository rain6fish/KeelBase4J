// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One version a policy has been: its content fingerprint, the content, and when it was applied. */
@Entity
@Table(name = "governance_policy_history")
public class GovernancePolicyHistoryRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String revision;

    @Column(name = "policy_json", nullable = false)
    private String policyJson;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt = Instant.now();

    protected GovernancePolicyHistoryRow() {
    }

    public GovernancePolicyHistoryRow(String revision, String policyJson) {
        this.revision = revision;
        this.policyJson = policyJson;
    }

    public Long getId() {
        return id;
    }

    public String getRevision() {
        return revision;
    }

    public String getPolicyJson() {
        return policyJson;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }
}
