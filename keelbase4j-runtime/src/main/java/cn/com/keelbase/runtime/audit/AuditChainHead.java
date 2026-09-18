// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The chain's write-lock row: one row every appender must hold for the whole of its transaction.
 *
 * <p>It carries no data and exists only to be locked. A JVM monitor is not enough, for two reasons.
 * It is released when the appending method returns while the transaction commits <em>after</em> that,
 * so a second appender can read the same chain head inside the window and fork the chain — that
 * window is open even in a single process. And a monitor is per-process, so it says nothing about a
 * second instance appending to the same database. A row lock is held until the transaction ends, so
 * it closes the window and covers both cases.
 */
@Entity
@Table(name = "audit_chain_head")
public class AuditChainHead {

    /** The only id this row ever has: one deployment appends to one chain. */
    public static final long SINGLETON = 1L;

    @Id
    private Long id;

    protected AuditChainHead() {
    }

    public AuditChainHead(long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
