// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a planner decided to call: a tool and its arguments, and nothing else.
 *
 * <p>It deliberately carries no risk level, no confirmation flag and no revoke class. Those are the
 * runtime's facts, read from the tool's own declaration — a plan that could set them would be a plan
 * that could talk its way past the gate, which is the one thing this boundary must not allow.
 *
 * @param tool the tool to call, by name
 * @param args the arguments to call it with
 */
public record IntentPlan(String tool, Map<String, Object> args) {

    public IntentPlan {
        // A defensive copy that tolerates null values — an absent argument is a real thing to pass
        // on, and Map.copyOf would reject it.
        args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }
}
