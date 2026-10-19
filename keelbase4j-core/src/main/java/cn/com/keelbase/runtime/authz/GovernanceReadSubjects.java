// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.authz;

import java.util.List;

/**
 * The subjects this runtime's governance read faces answer to, in one place.
 *
 * <p>Until JV-63 these surfaces were not on the capability seam at all — each controller inlined
 * {@code principal.isManager()}. Once they call {@link OwnershipGuard#requireAction} the subject is
 * a string the rule source must agree with, and the same string is what tells
 * {@link PermissionAuthorizer#describe} that a read-only principal is an <em>audit reader</em>
 * rather than an ordinary user. Two copies of these names would drift, so there is one.
 *
 * <p>The contract fixes the <em>shape</em> of a rule ({@code subject} × {@code action}) and leaves the
 * subject set per application; this class is this application's set. Naming a new subject grants
 * nothing by itself — a rule still has to declare it.
 *
 * <p>本运行时治理读面所答的 subject，集中在这一处。
 *
 * <p>JV-63 之前这些面根本不走能力缝——每个控制器内联 {@code principal.isManager()}。一旦它们改调
 * {@link OwnershipGuard#requireAction}，subject 就成了规则源必须同意的一个字符串；而同一个字符串也用来让
 * {@link PermissionAuthorizer#describe} 认出「只读的 principal 是审计读者、不是普通用户」。这些名字抄成
 * 两份就会漂，所以只有一份。
 *
 * <p>契约只冻结规则的**形状**（{@code subject} × {@code action}），subject 集合由每个应用自定；本类就是
 * 本应用的那一份。点名一个新 subject 本身不授予任何东西——仍要有一条规则声明它。
 */
public final class GovernanceReadSubjects {

    /** The AI audit chain: {@code /audit/verify} and {@code /audit/logs}. */
    public static final String AUDIT_LOG = "AuditLog";

    /** The operations audit: business writes, including the ones no AI took part in. */
    public static final String OPERATION_AUDIT = "OperationAudit";

    /** The tool inventory: what this runtime can be asked to do, and how each tool is governed. */
    public static final String TOOL_INVENTORY = "ToolInventory";

    /** The governance policy: readable, and writable under the {@code manage} action. */
    public static final String GOVERNANCE_POLICY = "GovernancePolicy";

    /** Recorded AI write side effects — read-only here; revoking stays on its own terms. */
    public static final String TOOL_EFFECT = "ToolEffect";

    /** Confirmation records, read-only across callers — deciding is a different entry and a different mode. */
    public static final String CONFIRMATION = "Confirmation";

    /**
     * The integration-health surface: what this deployment registered, whether its gates are in force,
     * whether audit rows are landing, and whether the delegation chain can be exercised.
     *
     * 接入健康度面：本部署注册了什么、门控是否在生效、审计行是否在落库、委托链路是否能被实际走一遍。
     */
    public static final String INTEGRATION_STATUS = "IntegrationStatus";

    /** Every subject above, for callers that ask "is this one of the governance read faces". */
    public static final List<String> ALL = List.of(
            AUDIT_LOG, OPERATION_AUDIT, TOOL_INVENTORY, GOVERNANCE_POLICY, TOOL_EFFECT, CONFIRMATION,
            INTEGRATION_STATUS);

    private GovernanceReadSubjects() {
    }
}
