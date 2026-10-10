// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.identity.ActingCaller;
import cn.com.keelbase.runtime.identity.ActingCallerId;
import cn.com.keelbase.runtime.pipeline.IntentPlan;
import cn.com.keelbase.runtime.pipeline.ModuleRoute;
import cn.com.keelbase.runtime.pipeline.ModuleRoutePlanner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The four shapes two hosts arrived at independently, now in this module.
 *
 * <p>What is pinned here is the part that made them worth lifting — not that the types exist, but that
 * the <em>conventions</em> inside them hold: the caller is restored rather than left behind, the
 * request's identity is asked before the published one, and a deployment's routes get the first word
 * while the runtime's rules stay the floor. A copy of a shape that lost its convention would still
 * compile and still look right.
 *
 * 两个宿主各自独立走到的四个形状，现在住在本模块里。
 *
 * <p>这里钉的是**它们值得被上提的那一部分** —— 不是「类型在」，而是**它们里面的约定站得住**：调用者是**被
 * 还原**的、不是被留在那儿；**请求自己的身份先被问**；部署方的路由**先说**，而运行时的规则**仍是地板**。
 * 一个把约定弄丢了的形状副本**照样编译、照样看着对**。
 */
class LiftedHostShapesTest {

    @Test
    void thePublishedCallerIsRestoredRatherThanLeftBehind() {
        assertEquals("outer", ActingCaller.as("outer", () -> {
            assertEquals("outer", ActingCaller.current());
            assertEquals("inner", ActingCaller.as("inner", ActingCaller::current));
            // Nested publication restores the one around it, not the absence of one.
            assertEquals("outer", ActingCaller.current());
            return ActingCaller.current();
        }));
        assertNull(ActingCaller.current(), "the thread is as it was before the call that owned it");
    }

    @Test
    void theRequestsOwnIdentityIsAskedFirst() {
        Long id = ActingCaller.as("7", () -> ActingCallerId.current(() -> 42L));

        assertEquals(42L, id, "the request's caller is the caller, whoever else published itself");
    }

    @Test
    void andThePublishedCallerAnswersWhenTheRequestHasNone() {
        // Both ways the request side can have nothing to say: a null, and a security context that was
        // never filled — which throws rather than answering.
        assertEquals(7L, ActingCaller.as("7", () -> ActingCallerId.current(() -> null)));
        assertEquals(7L, ActingCaller.as("7", () -> ActingCallerId.current(() -> {
            throw new IllegalStateException("no security context on the engine's tool path");
        })));
    }

    @Test
    void aCallerNobodyCanNameIsNullRatherThanAnInventedId() {
        assertNull(ActingCallerId.current(() -> null), "neither place can name one");
        assertNull(ActingCaller.as("alice", () -> ActingCallerId.current(() -> null)),
                "a caller name is not a caller id");
    }

    @Test
    void theDeploymentsRoutesAreAskedBeforeTheRuntimesRules() {
        AHostPlanner planner = new AHostPlanner();

        IntentPlan own = planner.plan("盘点一下这个客户", Map.of()).orElseThrow();
        assertEquals("module_tool", own.tool(), "the deployment's own route answers first");
        assertEquals("盘点一下这个客户", own.args().get("message"),
                "and it is handed the turn's message, which the runtime's context does not carry");

        // The runtime's rules are the floor, not a competitor: a phrase only they know still routes,
        // and they are asked with the context as it was — not with the message added to it.
        IntentPlan byRules = planner.plan("分析客户风险", Map.of("customerId", 7L)).orElseThrow();
        assertEquals("analyze_customer_risk", byRules.tool());
        assertEquals(7L, byRules.args().get("customerId"));

        assertTrue(planner.plan("no route and no rule answers this", Map.of()).isEmpty(),
                "nothing recognised is a proposal's absence, not a refusal");
    }

    /** A host's planner in the lifted shape: its own routes first, the runtime's rules last. */
    private static final class AHostPlanner extends ModuleRoutePlanner {

        @Override
        protected List<ModuleRoute> routes() {
            return List.of(new ModuleRoute() {
                @Override
                public String toolName() {
                    return "module_tool";
                }

                @Override
                public List<String> triggers() {
                    return List.of("盘点");
                }

                @Override
                public Map<String, Object> args(Map<String, Object> context) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("message", context.get("message"));
                    return args;
                }
            });
        }
    }
}
