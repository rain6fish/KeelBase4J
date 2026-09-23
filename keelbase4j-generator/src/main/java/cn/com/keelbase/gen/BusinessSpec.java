// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.gen;

import java.util.List;

/**
 * The Business Specification — the human-reviewable output of S1 and the input to generation (S2).
 *
 * <p>It is deliberately small: the entities and their fields, the ownership rule, the AI tools with
 * their governance metadata, and any policy rules (e.g. "only a manager may update a customer").
 * It is <b>not</b> a runtime dialect — it is a source from which ordinary Java is generated.
 *
 * @param module      the module's machine name ({@code crm}) — package, artifact, feature key
 * @param moduleLabel the module's name as the caller described it ("客户管理"). The capability surface
 *                    reports it, and a label belongs to the module rather than to one of its entities:
 *                    naming it after the first entity is a label that stops being true the moment the
 *                    module has a second one
 */
public record BusinessSpec(
        String module,
        String moduleLabel,
        List<EntitySpec> entities,
        RoleRule roleRule,
        List<ToolSpec> tools,
        List<PolicyRule> policies) {

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
            String description,
            /**
             * How a message reaches this tool when there is no model to route it — the words the request
             * itself used ("分析", "跟进"). This is a fallback for an application that runs without one,
             * not a classifier: a deployment that puts a model behind the planner seam never reads these.
             */
            List<String> triggers) {
    }

    /** A policy rule: {@code action} on {@code entity} requires {@code requiredRole}. */
    public record PolicyRule(String entity, String action, String requiredRole) {
    }
}
