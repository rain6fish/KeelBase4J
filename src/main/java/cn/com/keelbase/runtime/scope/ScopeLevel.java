// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.scope;

/**
 * How wide a row set an identity sees — the fine gate behind the decision function's coarse one.
 *
 * <p><b>These values never reach the wire.</b> The frozen {@code permission-capability-list.scope}
 * enum is exactly {@code all} / {@code own} (main repo PC-1). Widening it would be a contract change
 * made to explain a deployment's row filtering, and it would make a capability depend on the data.
 * So the decision function keeps answering "may this action on this subject happen at all" and the
 * level answers "which rows", kept apart as the reference's {@code docs/data-scope.spec.md} §1
 * requires.
 */
public enum ScopeLevel {

    /** The caller's own rows, and no others. */
    OWN("own"),

    /** Rows belonging to the caller's organization. */
    ORG("org"),

    /** The caller's own rows, plus their department's. */
    OWN_DEPT("own_dept"),

    /** The caller's own rows, plus their department's and everything beneath it. */
    OWN_DEPT_AND_BELOW("own_dept_and_below"),

    /** An explicit set of departments. */
    CUSTOM_DEPT("custom_dept"),

    /** No row-level condition at all — the coarse gate alone decides. */
    ALL("all");

    private final String referenceName;

    ScopeLevel(String referenceName) {
        this.referenceName = referenceName;
    }

    /** The name the reference implementation uses, so the two can be compared case by case. */
    public String referenceName() {
        return referenceName;
    }
}
