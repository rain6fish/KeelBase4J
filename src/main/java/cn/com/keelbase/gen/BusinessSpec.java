// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.gen;

import java.util.List;

/**
 * The Business Specification — the human-reviewable output of S1 and the input to generation (S2).
 *
 * <p>It is deliberately small: the entities and their fields, the ownership rule, and the AI tools
 * with their governance metadata. It is <b>not</b> a runtime dialect — it is a source from which
 * ordinary Java is generated.
 */
public record BusinessSpec(
        String module,
        List<EntitySpec> entities,
        RoleRule roleRule,
        List<ToolSpec> tools) {

    /** An entity and its fields. */
    public record EntitySpec(String name, List<FieldSpec> fields) {
    }

    /** A field: a name and one of the supported primitive types. */
    public record FieldSpec(String name, String type, String label) {
    }

    /** The row-level ownership rule: a role sees its own rows, a manager sees all. */
    public record RoleRule(String ownerField, String role, String description) {
    }

    /** An AI tool and the governance metadata the runtime needs to gate it. */
    public record ToolSpec(
            String name,
            String riskLevel,
            boolean requiresConfirmation,
            String resultType,
            String description) {
    }
}
