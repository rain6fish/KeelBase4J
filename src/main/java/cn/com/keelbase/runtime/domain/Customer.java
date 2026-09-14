// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A customer owned by one user (own-scope is the spike's row-level permission). */
@Entity
@Table(name = "customers")
public class Customer {

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
}
