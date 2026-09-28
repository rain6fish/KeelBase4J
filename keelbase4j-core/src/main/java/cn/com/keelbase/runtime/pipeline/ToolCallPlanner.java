// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import java.util.Map;
import java.util.Optional;

/**
 * Where an AI decides what to call — the seam an AI pipeline plugs into.
 *
 * <p>The runtime ships one implementation that routes by rules ({@link RuleBasedPlanner}), so the
 * trust loop is reproducible without a model. A Spring AI or LangChain4j adapter implements this same
 * interface and returns the tool it wants; everything after that is unchanged. That is the whole point
 * of the seam, and it is worth stating plainly:
 *
 * <p><b>A planner decides what to call, never whether it may run.</b> The risk level, the confirmation
 * requirement, the audit entry and the revoke path all come from the tool's own declaration and are
 * applied by the engine, downstream and unconditionally. So a planner — however clever, and whichever
 * model drives it — cannot propose its way past the gate.
 *
 * <p>Deliberately not here: model or provider configuration. Choosing a provider is a deployment
 * concern, and a runtime that grew its own abstraction for it would be owning a pipeline rather than
 * accepting one. Exactly one implementation must be a bean.
 */
public interface ToolCallPlanner {

    /**
     * What to call for this message, or empty when nothing fits.
     *
     * @param message the caller's words
     * @param context the caller-supplied fields a plan may draw on (e.g. an entity id); never
     *                authorization facts, which the runtime reads from the identity
     */
    Optional<IntentPlan> plan(String message, Map<String, Object> context);
}
