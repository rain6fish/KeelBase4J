// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.demo;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.springai.GovernedTaskRunner;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The multi-step path, reachable over HTTP — so that "the framework's loop really does drive governed
 * steps" is something you can ask a running deployment, not something only a test can claim.
 *
 * <p><b>Why this class is not in this module's own package.</b> The runtime's security chain scopes
 * itself to the paths its <em>own</em> controllers serve, and it decides "own" by package
 * ({@code OwnedRoutes}: {@code cn.com.keelbase.runtime}). A controller anywhere else would not be
 * matched by that chain — and a request no chain matches is not refused, it is simply unfiltered, so
 * an endpoint in {@code cn.com.keelbase.demo} would have been an unauthenticated one. Living under
 * {@code cn.com.keelbase.runtime.demo} makes it governed like every other route here. It ships in the
 * demo jar and nowhere else: no library depends on this module, so a host never grows this route.
 *
 * <p><b>Absent a model, the route answers 503 rather than an empty run.</b> A run with no steps is a
 * real result — the model can legitimately answer without calling anything — so it cannot also be how
 * "there is no model here" is reported. Those are different facts and only one of them is the caller's
 * fault.
 *
 * <p>多步路径，可以在 HTTP 上够到 —— 于是「框架的循环**真的**在驱动受治理的步骤」这件事，是**可以去问一个
 * 跑着的部署**的，而不是只有测试才能主张的。
 *
 * <p><b>这个类为什么不在本模块自己的包里。</b>运行时的安全链把自己限在**它自己的控制器**所服务的路径上，
 * 而「自己的」是按**包**判的（{@code OwnedRoutes}：{@code cn.com.keelbase.runtime}）。放在别处的控制器不会被
 * 那条链匹配 —— 而**没有链匹配的请求不是被拒，是不被过滤**，所以放在 {@code cn.com.keelbase.demo} 里的端点会是
 * 一个**未经认证**的端点。住在 {@code cn.com.keelbase.runtime.demo} 让它和这里其它每一条路由一样受治理。
 * 它只随 demo 那个 jar 走：没有任何库依赖本模块，故宿主**永远不会**长出这条路由。
 *
 * <p><b>没有模型时，这条路由答 503，而不是一次空的运行。</b>「一步都没有」是个**真结果** —— 模型完全可以不调
 * 任何东西就作答 —— 所以它不能同时又是「这里没有模型」的报法。那是两件不同的事实，而只有一件是调用方的问题。
 */
@RestController
public class GovernedTaskController {

    /** Absent when this deployment has no model provider configured — see the class javadoc. */
    private final ObjectProvider<GovernedTaskRunner> runner;
    private final CurrentPrincipal principals;

    public GovernedTaskController(ObjectProvider<GovernedTaskRunner> runner, CurrentPrincipal principals) {
        this.runner = runner;
        this.principals = principals;
    }

    /**
     * Body of {@code POST /ai/task}: the words, and nothing else.
     *
     * <p>Deliberately not {@code ChatRequest}: that one carries a customer id and a conversation id, and
     * this path honours neither — the model is given the message and the tools, and resolves whatever
     * the message refers to by reading it, exactly as a caller of the single-shot path would have to
     * write it. Accepting fields that are then ignored is how a caller comes to believe they were used.
     *
     * <p>{@code POST /ai/task} 的请求体：只有那句话。
     *
     * <p>刻意**不用** {@code ChatRequest}：那个带着客户 id 与会话 id，而本路径**两者都不认** —— 模型拿到的是
     * **消息与工具**，消息提到的东西由它**读**出来，正如单发路径的调用方也必须这么写。收下随后被忽略的字段，
     * 正是**调用方开始以为它们生效了**的原因。
     */
    public record TaskRequest(String message) {
    }

    /**
     * Runs the message as a multi-step task and reports the run: the model's answer, every governed
     * step in the order the loop took them, and the token of a step still waiting on a person.
     *
     * <p>Nothing here decides what may run. The caller is authenticated by the same chain as every
     * other route, and each step is a tool call that the engine gated on its own.
     *
     * <p>把这条消息当作多步任务跑，并汇报这次运行：模型的答复、循环按序走的每一步受治理的调用、以及某个仍在
     * 等人的步骤的 token。
     *
     * <p>这里**没有任何东西**决定什么可以跑：调用方由与其它每条路由同一条链完成认证，而每一步都是一次**由引擎
     * 自己**把关的工具调用。
     */
    @PostMapping("/ai/task")
    public Map<String, Object> task(@RequestBody TaskRequest request) {
        GovernedTaskRunner tasks = runner.getIfAvailable();
        if (tasks == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "this deployment has no model provider, so there is no multi-step path to drive");
        }
        Principal principal = principals.current();
        GovernedTaskRunner.TaskRun run = tasks.run(request.message(), principal);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answer", run.answer());
        body.put("pendingToken", run.pendingToken());
        body.put("calls", run.calls().stream().map(GovernedTaskController::step).toList());
        return body;
    }

    /** One step, in the same shape the single-shot path reports its outcome. */
    private static Map<String, Object> step(GovernedTaskRunner.Step step) {
        ExecutionOutcome outcome = step.outcome();
        Map<String, Object> said = new LinkedHashMap<>();
        said.put("status", outcome.status());
        said.put("data", outcome.data());
        said.put("token", outcome.token());
        said.put("effectId", outcome.effectId());
        said.put("error", outcome.error());

        Map<String, Object> one = new LinkedHashMap<>();
        one.put("tool", step.tool());
        one.put("outcome", said);
        return one;
    }
}
