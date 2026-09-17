// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.domain;

import cn.com.keelbase.runtime.scope.ScopedRow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A customer owned by one user.
 *
 * <p>It carries the organization and department it belongs to as well as its owner, because a row
 * range may name either: "my rows", "my organization's", "my department's" (see
 * {@code runtime.scope}). A row written without them is reachable by its owner only, which is the
 * tightening direction — the facts being absent must never be what makes a row visible.
 */
@Entity
@Table(name = "customers")
public class Customer implements ScopedRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** low | medium | high. */
    @Column(nullable = false)
    private String level = "low";

    @Column(name = "owner_user_id", nullable = false)
    private String ownerUserId;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "dept_id")
    private Long deptId;

    protected Customer() {
    }

    public Customer(String name, String level, String ownerUserId) {
        this.name = name;
        this.level = level;
        this.ownerUserId = ownerUserId;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
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
