// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explainable Authz — a single decision: "may this principal perform this action on this subject?"
 *
 * <p>Java carrier of the frozen wire contract {@code permission-decision.schema.json} (main repo,
 * {@code POST /auth/permissions/explain}). The contract freezes the shape and the closed values
 * ({@code allowed}; {@code deniedBy} null when allowed). The wording below is the reference's, kept
 * verbatim so the two runtimes' decisions stay directly comparable — a shared frontend renders the
 * same text on either runtime (Rev-8).
 *
 * @param action   the action being asked about (e.g. {@code read})
 * @param subject  the resource it applies to (e.g. {@code Customer})
 * @param allowed  whether the principal may perform it
 * @param reason   human-readable basis
 * @param deniedBy the deny basis when refused, {@code null} when allowed
 */
public record PermissionDecision(String action, String subject, boolean allowed, String reason, String deniedBy) {

    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_OWN = "own";

    /**
     * The deny basis. {@code "casl"} is the frozen contract's name for the <em>ability/condition
     * layer</em> that refused — not a reference to any library. The Java runtime reproduces that
     * layer's semantics itself and reports the same basis, so a decision taken on either runtime
     * carries the same value (ADR-0004 Considered Option E).
     */
    public static final String DENIED_BY_CASL = "casl";

    public static final String REASON_ALLOWED_ALL = "管理员：可管理全部资源";
    public static final String REASON_ALLOWED_OWN = "可操作（本人所有权范围，行级条件）";
    public static final String REASON_DENIED_ADMIN = "当前策略不允许此操作";
    public static final String REASON_DENIED_USER = "需要管理员权限，或该资源不在你的可管理范围";

    /** The wire shape: exactly the contract's properties, {@code deniedBy} present-but-null when allowed. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("action", action);
        wire.put("subject", subject);
        wire.put("allowed", allowed);
        wire.put("reason", reason);
        wire.put("deniedBy", deniedBy);
        return wire;
    }
}
