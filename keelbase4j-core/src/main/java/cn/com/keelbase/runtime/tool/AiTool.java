// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

import cn.com.keelbase.protocol.RiskLevel;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.Map;

/**
 * A governable AI tool — the minimum a tool must declare for the runtime to gate it.
 *
 * <p>Governance-relevant metadata (risk level, whether it needs confirmation, its revoke class)
 * is server-side only and never sent to a model. {@code execute} receives the acting principal,
 * so data isolation is a property of the tool, not of the caller.
 */
public interface AiTool {

    String name();

    String description();

    /** Declared risk level (R0–R5). */
    String riskLevel();

    /** Derived from the risk level unless a tool overrides it. */
    default boolean requiresConfirmation() {
        return RiskLevel.needsConfirmation(riskLevel());
    }

    /** The business result type this tool creates (e.g. {@code follow_up}), or null for reads. */
    default String resultType() {
        return null;
    }

    /** Revoke class (§2.3 revoke contract): {@code none} for reads, {@code local_compensate} for writes. */
    default String revokeClass() {
        return requiresConfirmation() ? "local_compensate" : "none";
    }

    ToolResult execute(Map<String, Object> args, Principal principal);
}
