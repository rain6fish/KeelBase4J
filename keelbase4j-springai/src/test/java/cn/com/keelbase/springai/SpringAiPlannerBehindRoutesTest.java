// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.KeelBase4JApplication;
import cn.com.keelbase.runtime.governance.ConfirmationMode;
import cn.com.keelbase.runtime.governance.ConfirmationRequest;
import cn.com.keelbase.runtime.governance.ConfirmationRequestRepository;
import cn.com.keelbase.runtime.pipeline.IntentPlan;
import cn.com.keelbase.runtime.pipeline.ModuleRoute;
import cn.com.keelbase.runtime.pipeline.ModuleRoutePlanner;
import cn.com.keelbase.runtime.pipeline.RuleBasedPlanner;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * A deployment that takes the seam <em>and</em> reaches its model — through the composition port, not
 * around it.
 *
 * <p>This is the arrangement {@code SpringAiPlannerOnTheSeamTest} cannot cover and the one two hosts had
 * no way to express. A model-backed planner is registered {@code @ConditionalOnMissingBean}, so the very
 * deployment that declares routes suppresses it: taking the seam and reaching a configured model cannot
 * both be true through registration alone. {@link ModuleRoutePlanner}'s constructor is the way out, and
 * this test is what says so — a route planner whose fallback is the runtime's rules and then the model.
 *
 * <p>Three things have to hold, and each is a different failure:
 *
 * <ol>
 *   <li><b>The deployment still owns the seam.</b> It is its own planner that answers, and the model
 *       planner is not a second bean competing for the slot.
 *   <li><b>Nothing already answered is now asked of the model.</b> A phrase the deployment's routes know,
 *       and a phrase the runtime's rules know, are both answered without the model being consulted at
 *       all. The composition can only turn "nothing proposed" into "a proposal" — it can never change a
 *       proposal that already existed.
 *   <li><b>What nothing knows is answered by the model</b> — and the proposal is still only a proposal.
 * </ol>
 *
 * 一个**既占了这条缝、又走得到自己那个模型**的部署 —— 走的是组合口，不是绕过它。
 *
 * <p>这正是 `SpringAiPlannerOnTheSeamTest` 覆盖不到、而两个宿主本来**没有出口**去表达的那个安排。模型驱动的
 * 规划器是按 `@ConditionalOnMissingBean` 注册的，所以**声明了路由的那个部署正把它顶掉**：靠注册本身，「占住
 * 这条缝」与「走到一个配了的模型」不可能同时成立。出口是 {@link ModuleRoutePlanner} 的构造参数，而这份测试就是
 * 那句话的凭据 —— 一个路由规划器，兜底先是运行时的规则、然后是模型。
 *
 * <p>三件事必须同时成立，各是一种不同的失败：
 *
 * <ol>
 *   <li><b>缝仍归**部署方**。</b>作答的是它自己的规划器，而模型那个**不是**第二个来抢这个槽的 bean。
 *   <li><b>本来答得上的，不会再去问模型。</b>部署方路由认得的短语、以及运行时规则认得的短语，**都不问模型**。
 *       这次组合只能把「没有提议」变成「有提议」——**改不了任何已经存在的提议**。
 *   <li><b>谁都不认得的，由模型作答</b> —— 而它的提议**仍然只是提议**。
 * </ol>
 */
@SpringBootTest(classes = KeelBase4JApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@Import(SpringAiPlannerBehindRoutesTest.Deployment.class)
class SpringAiPlannerBehindRoutesTest {

    /** The turn's message reaches the route inside the context, which is where a note comes from. */
    private static final String ROUTE_TRIGGER = "回访";

    /** @TestConfiguration: the deployment, its routes, and a model that always proposes the same write. */
    @TestConfiguration
    static class Deployment {

        static final List<String> PROMPTS = new ArrayList<>();

        @Bean
        ChatModel chatModel() {
            return prompt -> {
                PROMPTS.add(prompt.getContents());
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"tool\": \"create_followup\", \"args\": {\"customerId\": 1, \"note\": \"from the model\"}}"))));
            };
        }

        /**
         * The deployment's planner, in the shape the port exists for: its own routes take the seam, and
         * the model waits behind the runtime's rules. Constructed here rather than injected, because a
         * model-backed planner is not registered as a bean once this one has taken the slot — which is
         * the whole reason the constructor takes a fallback.
         */
        @Bean
        ToolCallPlanner deploymentPlanner(ChatClient.Builder builder, ToolRegistry tools) {
            ToolCallPlanner model = new SpringAiToolCallPlanner(builder.build(), tools);
            ToolCallPlanner rules = new RuleBasedPlanner();
            return new RoutesThenFallback(List.of(followUpRoute()),
                    (message, context) -> rules.plan(message, context)
                            .or(() -> model.plan(message, context)));
        }

        /** One route, on a phrase the runtime's rules do not know — so which of the two answered is visible. */
        private static ModuleRoute followUpRoute() {
            return new ModuleRoute() {
                @Override
                public String toolName() {
                    return "create_followup";
                }

                @Override
                public List<String> triggers() {
                    return List.of(ROUTE_TRIGGER);
                }

                @Override
                public Map<String, Object> args(Map<String, Object> context) {
                    return Map.of("customerId", 1, "note", "from the route");
                }
            };
        }
    }

    /** Routes first, then whatever the deployment put behind them — the port, used as it is meant to be. */
    static final class RoutesThenFallback extends ModuleRoutePlanner {

        private final List<ModuleRoute> routes;

        RoutesThenFallback(List<ModuleRoute> routes, ToolCallPlanner fallback) {
            super(fallback);
            this.routes = routes;
        }

        @Override
        protected List<ModuleRoute> routes() {
            return routes;
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApplicationContext context;

    @Autowired
    ConfirmationRequestRepository confirmations;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void theDeploymentKeepsTheSeamAndTheModelIsNotASecondBean() {
        assertTrue(context.getBean(ToolCallPlanner.class) instanceof RoutesThenFallback,
                "the deployment's own planner answers; the model planner did not replace it");
        assertTrue(context.getBeansOfType(SpringAiToolCallPlanner.class).isEmpty(),
                "the model planner is composed behind the routes, not registered alongside them — "
                        + "exactly one implementation is a bean");
    }

    @Test
    void aPhraseItsOwnRoutesKnowNeverReachesTheModel() {
        long before = pending("alice");
        Deployment.PROMPTS.clear();

        chat("alice", ROUTE_TRIGGER + "一下这个客户");

        assertEquals(before + 1, pending("alice"),
                "the route proposed the write, and a proposal still waits for a human");
        assertTrue(Deployment.PROMPTS.stream().noneMatch(p -> p.contains("Available tools")),
                "the route answered it, so the model was never asked to plan: " + Deployment.PROMPTS);
    }

    @Test
    void aPhraseTheRuntimeRulesKnowNeverReachesTheModelEither() {
        Deployment.PROMPTS.clear();

        chat("alice", "分析一下这个客户");

        assertTrue(Deployment.PROMPTS.stream().noneMatch(p -> p.contains("Available tools")),
                "the runtime's rules answered it, so the model was not asked to plan: "
                        + Deployment.PROMPTS);
    }

    @Test
    void whatNothingElseKnowsIsAnsweredByTheModel() {
        long before = pending("alice");
        Deployment.PROMPTS.clear();

        chat("alice", "盘点一下这个客户");

        String plannerPrompt = Deployment.PROMPTS.stream()
                .filter(p -> p.contains("Available tools"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "nothing else recognised it, so the model should have been asked: "
                                + Deployment.PROMPTS));
        assertTrue(plannerPrompt.contains("create_followup"), "it is told which tools exist");
        assertFalse(plannerPrompt.contains("R3"), "and still not how the call is governed");
        assertEquals(before + 1, pending("alice"), "a proposal is not a permission: the write still waits");
    }

    /** The immediate-mode confirmations still waiting for this caller. */
    private long pending(String userId) {
        return confirmations.findByOperatorIdAndModeOrderByCreatedAtDesc(userId, ConfirmationMode.IMMEDIATE)
                .stream()
                .filter(row -> ConfirmationLifecycle.PENDING.equals(row.getStatus()))
                .count();
    }

    /** A turn's message, with the id the runtime reads a context for. */
    private void chat(String userId, String message) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(userId, delegationSecret));
        ResponseEntity<Map> res = rest.postForEntity("/ai/chat",
                new HttpEntity<>(Map.of("message", message, "customerId", 1), headers), Map.class);
        assertEquals(200, res.getStatusCode().value(), "the turn answered: " + res.getBody());
    }
}
