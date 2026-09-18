// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.scope;

/**
 * What a row range needs to know about one row — the three facts a scope-filterable row carries.
 *
 * <p>Implementing this is what makes an entity scope-filterable. It does not by itself grant access
 * to anything: the decision function still has to allow the action, and a row is only reachable when
 * both agree.
 */
public interface ScopedRow {

    /** The user this row belongs to. */
    String ownerUserId();

    /** The organization this row belongs to, or {@code null} when it has none. */
    Long orgId();

    /** The department this row belongs to, or {@code null} when it has none. */
    Long deptId();
}
