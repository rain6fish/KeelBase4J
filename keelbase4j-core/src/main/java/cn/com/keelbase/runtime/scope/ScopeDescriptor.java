// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.scope;

import java.util.Set;

/**
 * A row range as an internal descriptor — an object, never a SQL string, and never a wire value.
 *
 * <p>The reference keeps the same thing ({@code ScopeDescriptor} in {@code src/common/scope}) and
 * translates it into a query predicate with a pure function. Keeping it a descriptor rather than a
 * fragment of query text is what preserves explainability and leaves no injection surface.
 *
 * @param level   how wide the range is
 * @param deptIds the explicit department set, for {@link ScopeLevel#CUSTOM_DEPT}; empty otherwise
 */
public record ScopeDescriptor(ScopeLevel level, Set<Long> deptIds) {

    public ScopeDescriptor {
        deptIds = Set.copyOf(deptIds);
    }

    public static ScopeDescriptor of(ScopeLevel level) {
        return new ScopeDescriptor(level, Set.of());
    }

    public static ScopeDescriptor customDept(Set<Long> deptIds) {
        return new ScopeDescriptor(ScopeLevel.CUSTOM_DEPT, deptIds);
    }
}
