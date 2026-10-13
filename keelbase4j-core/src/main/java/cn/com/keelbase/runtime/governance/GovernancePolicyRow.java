// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The one row holding the governance policy, as the JSON the frozen object describes. */
@Entity
@Table(name = "governance_policy")
public class GovernancePolicyRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_json", nullable = false)
    private String policyJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected GovernancePolicyRow() {
    }

    public GovernancePolicyRow(String policyJson) {
        this.policyJson = policyJson;
    }

    public Long getId() {
        return id;
    }

    public String getPolicyJson() {
        return policyJson;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setPolicyJson(String policyJson) {
        this.policyJson = policyJson;
        this.updatedAt = Instant.now();
    }
}
