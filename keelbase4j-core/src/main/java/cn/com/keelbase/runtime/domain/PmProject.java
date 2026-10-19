// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.domain;

import cn.com.keelbase.runtime.scope.ScopedRow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A project owned by one user — the PM reference project's read target.
 *
 * <p>It is the second sample module, beside the CRM one, and it exists to show the same trust loop
 * over a different business domain rather than to add a feature. The row carries the same scope facts
 * a customer does (owner, organization, department) so the row range is decided the same way.
 *
 * <p>Projects are not written by a tool in this reference: the write tools create {@link PmTask} rows,
 * which keeps every AI write soft-deletable and therefore locally compensable.
 *
 * <p>由一个用户拥有的项目 —— PM 参考项目的读目标。
 *
 * <p>它是 CRM 之外的第二个示例模块，存在是为了在**另一个业务域**上演示**同一条信任闭环**，而不是为了加功能。
 * 这一行带着与客户相同的范围事实（所有者、组织、部门），故行范围的裁决方式完全一致。
 *
 * <p>本参考项目里**没有工具会写项目**：写工具创建的是 {@link PmTask} 行，于是每一次 AI 写都可软删、
 * 也就可以本地补偿。
 */
@Entity
@Table(name = "pm_projects")
public class PmProject implements ScopedRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** active | on_hold | done. */
    @Column(nullable = false)
    private String status = "active";

    /** low | medium | high — the declared signal the deadline-risk analysis starts from. */
    @Column(name = "risk_level", nullable = false)
    private String riskLevel = "low";

    @Column(length = 1000)
    private String description;

    @Column(name = "owner_user_id", nullable = false)
    private String ownerUserId;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "dept_id")
    private Long deptId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PmProject() {
    }

    public PmProject(String name, String status, String riskLevel, String description,
                     String ownerUserId) {
        this.name = name;
        this.status = status;
        this.riskLevel = riskLevel;
        this.description = description;
        this.ownerUserId = ownerUserId;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getDescription() {
        return description;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public Long getDeptId() {
        return deptId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Stamps the organization this row belongs to; both are written together, or not at all. */
    public void assign(Long orgId, Long deptId) {
        this.orgId = orgId;
        this.deptId = deptId;
    }

    @Override
    public String ownerUserId() {
        return ownerUserId;
    }

    @Override
    public Long orgId() {
        return orgId;
    }

    @Override
    public Long deptId() {
        return deptId;
    }
}
