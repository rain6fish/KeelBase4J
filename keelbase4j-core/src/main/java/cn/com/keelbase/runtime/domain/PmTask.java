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
import java.time.LocalDate;

/**
 * A task under a project. Created only by AI (the PM reference's write tools); soft-deletable so a
 * revoke can compensate it locally.
 *
 * <p>The due date is what the deadline-risk analysis reads, which is why it is a {@link LocalDate}
 * rather than free text: a date the analysis cannot compare is not a date.
 *
 * <p>项目下的一条任务。只由 AI 创建（PM 参考项目的写工具）；可软删，故撤销能本地补偿它。
 *
 * <p>到期日正是延期风险分析读的那个字段，所以它是 {@link LocalDate} 而不是自由文本：分析无法比较的日期，
 * 就不是日期。
 */
@Entity
@Table(name = "pm_tasks")
public class PmTask implements ScopedRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private String title;

    /** open | done. */
    @Column(nullable = false)
    private String status = "open";

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "owner_user_id", nullable = false)
    private String ownerUserId;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "dept_id")
    private Long deptId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Soft-delete marker (revoke = local compensation). */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected PmTask() {
    }

    public PmTask(Long projectId, String title, LocalDate dueDate, String ownerUserId) {
        this.projectId = projectId;
        this.title = title;
        this.dueDate = dueDate;
        this.ownerUserId = ownerUserId;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getDueDate() {
        return dueDate;
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

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    /** Marks the task done, so the deadline-risk analysis stops counting it. */
    public void markDone() {
        this.status = "done";
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
