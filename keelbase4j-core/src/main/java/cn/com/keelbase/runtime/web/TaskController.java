// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.TaskRun;
import cn.com.keelbase.runtime.pipeline.TaskRunner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The multi-step entry: one message, and the framework's own loop decides how many governed steps it
 * takes to answer it.
 *
 * <p>It lives here rather than in an adapter for the reason every other route does: the caller proves
 * who they are the same way it does for {@code /ai/chat}, and this path is governed by the same chain.
 * An adapter supplies <em>what runs</em> ({@link TaskRunner}); this runtime supplies <em>where you
 * ask</em> — so a host that embeds this runtime gets the route without adding one of its own, and
 * there is one implementation of it rather than one per deployment.
 *
 * <p><b>A deployment without a runner is answered, not refused.</b> {@code available: false} with a
 * reason, as a 200 — the shape this runtime already uses when a deployment lacks something
 * ({@code /auth/oauth/providers} answers an empty list, {@code /auth/login-stats} answers
 * {@code ok: false}). Two cheaper-looking alternatives are both wrong here: a 5xx reaches the caller as
 * "服务器内部错误" and says nothing about what is missing, and an empty run claims that a run happened.
 * A missing capability and a run that did nothing have to look different from outside.
 *
 * <p>多步入口：一句话，由**框架自己的循环**决定要用几步受治理的调用把它答完。
 *
 * <p>它住在这里、而不是某个适配器里，理由和每一条别的路由一样：调用方证明身份的方式与 `/ai/chat` **同一条**，
 * 而本路径受**同一条链**治理。适配器提供的是**跑什么**（{@link TaskRunner}），本运行时提供的是**在哪儿问** ——
 * 于是嵌了本运行时的宿主**不必自己再加一条**，而它也只有**一份实现**，不是每个部署各一份。
 *
 * <p>**没有 runner 的部署会被如实回答，而不是被拒。** `available: false` 加一个原因，状态 200 —— 这正是本
 * 运行时在「部署缺了某样东西」时已经在用的形状（`/auth/oauth/providers` 答空列表、`/auth/login-stats` 答
 * `ok: false`）。两个看起来更省事的替代都错在这里：5xx 到调用方手上是「服务器内部错误」，**说不出缺了什么**；
 * 而一次空的运行会**声称跑过**。**「能力不在」与「跑了一次、什么都没做」必须从外面看得出区别。**
 */
@RestController
public class TaskController {

    /** What a deployment can do about it — the same actionable pair the error envelope carries. */
    private static final String NO_RUNNER_REASON =
            "no multi-step task implementation is on the classpath";
    private static final String NO_RUNNER_NEXT_STEP =
            "add an orchestration adapter (keelbase4j-springai, with a model provider configured); "
                    + "this runtime deliberately ships no implementation of its own";

    private final ObjectProvider<TaskRunner> runner;
    private final CurrentPrincipal principals;

    public TaskController(ObjectProvider<TaskRunner> runner, CurrentPrincipal principals) {
        this.runner = runner;
        this.principals = principals;
    }

    @PostMapping("/ai/task")
    public Map<String, Object> task(@RequestBody TaskRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }

        TaskRunner tasks = runner.getIfAvailable();
        Map<String, Object> body = new LinkedHashMap<>();
        if (tasks == null) {
            body.put("available", false);
            body.put("reason", NO_RUNNER_REASON);
            body.put("nextStep", NO_RUNNER_NEXT_STEP);
            body.put("answer", null);
            body.put("pendingToken", null);
            body.put("steps", List.of());
            return body;
        }

        Principal principal = principals.current();
        TaskRun run = tasks.run(request.message(), principal);

        body.put("available", true);
        body.put("answer", run.answer());
        body.put("pendingToken", run.pendingToken());
        body.put("steps", run.steps().stream().map(TaskController::step).toList());
        return body;
    }

    /** One step, in the same shape the single-shot path reports its outcome. */
    private static Map<String, Object> step(TaskRun.Step step) {
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
