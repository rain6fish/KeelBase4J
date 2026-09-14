// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A follow-up note on a customer. Created only by AI (the spike's R3 write tool); soft-deletable
 * so a revoke can compensate it locally.
 */
@Entity
@Table(name = "follow_ups")
public class FollowUp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private String note;

    @Column(name = "due_date")
    private String dueDate;

    @Column(name = "owner_user_id", nullable = false)
    private String ownerUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Soft-delete marker (revoke = local compensation). */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected FollowUp() {
    }

    public FollowUp(Long customerId, String note, String dueDate, String ownerUserId) {
        this.customerId = customerId;
        this.note = note;
        this.dueDate = dueDate;
        this.ownerUserId = ownerUserId;
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getNote() {
        return note;
    }

    public String getDueDate() {
        return dueDate;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
