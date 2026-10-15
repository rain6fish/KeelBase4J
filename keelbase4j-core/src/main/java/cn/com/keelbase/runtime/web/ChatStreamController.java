// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.governance.ConfirmationWatchers;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The streaming entry point, in the shape the web console reads (ADR-0010).
 *
 * <p>It exists because the console's writes need it, not for a typing effect. The confirmation card
 * is built from a {@code confirmation_request} event, and — the reason this stream stays open rather
 * than closing when the turn is done — <b>some decisions are not the client's to make</b>. A
 * confirmation decided on another surface, or one whose wait simply expires, has to reach an open
 * view; the drawer's own approve is settled by its own request and does not need this.
 *
 * <p>The stream therefore ends in one of two ways. A decision arrives — from anywhere — and the
 * stream reports it and closes. Or the wait runs out and nothing decided it, in which case the stream
 * closes <em>without</em> a decision, because under v2 an expired wait does not end a confirmation:
 * the write is still decidable, and saying otherwise here would be a lie the data does not support.
 *
 * <p>Both paths — {@code /ai/chat/stream} and {@code /admin/ai/chat/stream} — land here, because the
 * console picks its endpoint by the caller's role. They are the same handler rather than two
 * assistants: this runtime has one set of tools, and inventing a second set so that an endpoint name
 * looks implemented is exactly the kind of thing not to do. See ADR-0010 D5.
 */
@RestController
public class ChatStreamController {

    /**
     * Slack over the wait, so that the emitter's own timeout does not fire first and cut off the
     * {@code done} event the caller is waiting for.
     */
    private static final long TIMEOUT_SLACK_MILLIS = 5_000L;

    private final CurrentPrincipal principals;
    private final ChatTurnService turns;
    private final ConfirmationWatchers watchers;
    private final ToolRegistry tools;
    private final TaskScheduler scheduler;
    private final long waitMillis;

    public ChatStreamController(CurrentPrincipal principals, ChatTurnService turns,
                                ConfirmationWatchers watchers, ToolRegistry tools,
                                TaskScheduler scheduler,
                                @Value("${keelbase.chat.stream-wait-ms:0}") long configuredWait) {
        this.principals = principals;
        this.turns = turns;
        this.watchers = watchers;
        this.tools = tools;
        this.scheduler = scheduler;
        // How long a stream waits for a decision: the confirmation's own in-conversation window. A
        // deployment may shorten it (a test does); zero means "the contract's".
        this.waitMillis = configuredWait > 0 ? configuredWait : ConfirmationLifecycle.DEFAULT_TTL_MILLIS;
    }

    @PostMapping("/ai/chat/stream")
    public SseEmitter stream(@RequestBody ChatRequest request) {
        return open(request, principals.current());
    }

    /**
     * The console's endpoint when its caller is an administrator. The gate is the whole difference
     * between the two paths — same turn, same tools, same governance — and it is the reference's own
     * arrangement: its admin route carries {@code manage all}, and this runtime answers the same way
     * rather than pretending to have a second assistant behind the name (D5).
     */
    @PostMapping("/admin/ai/chat/stream")
    public SseEmitter adminStream(@RequestBody ChatRequest request) {
        Principal principal = principals.current();
        if (!principal.isManager()) {
            throw new AccessDeniedException(
                    "the management conversation endpoint is for administrators");
        }
        return open(request, principal);
    }

    private SseEmitter open(ChatRequest request, Principal principal) {
        SseEmitter emitter = new SseEmitter(waitMillis + TIMEOUT_SLACK_MILLIS);
        try {
            ChatTurnService.Turn turn = turns.run(
                    request.message(), request.targetId(), request.conversationId(), principal);

            if (turn.plan() != null) {
                send(emitter, "tool_start", Map.of("toolStart", toolStart(turn)));
            }
            send(emitter, "text", Map.of("content", turn.reply().text()));
            // A navigation turn names a route, and the console reads it off an event of its own rather
            // than out of the reply's prose: the reply says "opened" in whatever words the model chose,
            // and the route is the part that has to be acted on. The frozen event set declares this
            // name — the reference has it and this runtime did not emit it — so it is sent after the
            // text, where the console's own rendering of a turn has already begun.
            //
            // 导航回合会点名一条路径，控制台从**它自己的事件**里读、不从 reply 的散文里抠：reply 用模型挑的
            // 词说「已打开」，而**要被执行的是那条路径**。冻结的事件集**声明了这个名字** —— 参照实现有它、
            // 本运行时此前没发过 —— 所以它发在 text **之后**，那是控制台自己渲染一个回合**已经开始**的位置。
            String navigateTo = turn.navigateTo();
            if (navigateTo != null) {
                send(emitter, "navigate", Map.of("route", navigateTo));
            }

            ExecutionOutcome outcome = turn.outcome();
            if (outcome != null && "pending_confirmation".equals(outcome.status())) {
                send(emitter, "confirmation_request", Map.of("confirmation", confirmation(turn)));
                awaitDecision(emitter, outcome.token(), turn);
                return emitter;
            }
            finish(emitter, turn);
        } catch (RuntimeException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * Hold the stream open until somebody decides, then report and close. Registered against the
     * token, so the decision can arrive on a different request — which is the whole point.
     */
    private void awaitDecision(SseEmitter emitter, String token, ChatTurnService.Turn turn) {
        watchers.watch(token,
                decision -> {
                    send(emitter, "confirmation_decision", Map.of("confirmationDecision", decision));
                    send(emitter, "tool_end", Map.of("toolEnd", Map.of(
                            "name", turn.plan().tool(),
                            "success", Boolean.TRUE.equals(decision.get("success")))));
                    send(emitter, "done", Map.of("conversationId", turn.conversationId()));
                    emitter.complete();
                },
                () -> {
                    send(emitter, "done", Map.of("conversationId", turn.conversationId()));
                    emitter.complete();
                });

        // The wait's own deadline, a step ahead of the emitter's: the container's timeout would end
        // the response where it stands, with no closing event, and a caller reading that cannot tell
        // it from a dropped connection. Firing first is what lets this stream say it is done.
        ScheduledFuture<?> expiry = scheduler.schedule(
                () -> watchers.expired(token), Instant.now().plusMillis(waitMillis));

        Runnable release = () -> {
            watchers.stop(token);
            expiry.cancel(false);
        };
        emitter.onCompletion(release);
        emitter.onError(disconnected -> release.run());
        emitter.onTimeout(release);
    }

    private void finish(SseEmitter emitter, ChatTurnService.Turn turn) {
        if (turn.outcome() != null) {
            send(emitter, "tool_end", Map.of("toolEnd", Map.of(
                    "name", turn.plan().tool(),
                    "success", "executed".equals(turn.outcome().status()))));
        }
        send(emitter, "done", Map.of("conversationId", turn.conversationId()));
        emitter.complete();
    }

    /** A write is a tool that produces a result; a read produces none. */
    private Map<String, Object> toolStart(ChatTurnService.Turn turn) {
        AiTool tool = tools.require(turn.plan().tool());
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("name", tool.name());
        // The frozen `toolStart` requires `summary`, and this runtime was not sending it at all — a
        // required field of a frozen object, absent for as long as the event has existed. The reference
        // renders a sentence about *this call* for a write and a name-keyed one for a read; this runtime
        // has no presentation layer, so it uses the tool's own description — a name-keyed sentence — for
        // both, and says so here rather than inventing a way to arrange the arguments into prose.
        //
        // 冻结的 `toolStart` **要求** `summary`，而本运行时**根本没发过它** —— 一个冻结对象的**必填字段**，
        // 在该事件存在期间一直缺着。参照实现对**写**渲染一句关于**这次调用**的话、对**读**渲染一句**按名字**的话；
        // 本运行时没有那一层「呈现」，故两者都用**工具自己的描述**（一句按名字的话），并在这里说出来，而不是
        // 去发明一种「把参数排成散文」的写法。
        start.put("summary", tool.description());
        start.put("arguments", turn.plan().args());
        start.put("isWrite", tool.resultType() != null);
        // The risk level does belong on this channel: it goes to the user's own console, not to a
        // model. The rule about withholding it is about prompts.
        start.put("riskLevel", tool.riskLevel());
        return start;
    }

    private Map<String, Object> confirmation(ChatTurnService.Turn turn) {
        Map<String, Object> confirmation = new LinkedHashMap<>();
        confirmation.put("token", turn.outcome().token());
        confirmation.put("toolName", turn.plan().tool());
        confirmation.put("arguments", turn.plan().args());
        // R3 — the operator confirms their own write, which the frozen vocabulary calls `immediate`;
        // the runtime implements neither of the other two, `approval` (an R4 two-person decision) or
        // `run` (one authorisation for a whole batch). It used to say `"confirmation"` — a value that
        // appears in no enum the contract or the reference writes, and that this runtime's own read side
        // never spoke either: `/ai/my/confirmations` has always reported `immediate` for these rows.
        //
        // R3 —— 操作者确认自己的写，而冻结词表管这叫 `immediate`；另外两个本运行时**都不实现** ——
        // `approval`（R4 的双人裁决）与 `run`（对一整批的一次授权）。它此前写的是 `"confirmation"` ——
        // 一个**契约与参照实现都不写的取值**，而本运行时**自己的读侧**也从没说过它：
        // `/ai/my/confirmations` 对这些行一直报的就是 `immediate`。
        confirmation.put("mode", "immediate");
        return confirmation;
    }

    /**
     * One event. The {@code type} travels inside the JSON as well as on the {@code event:} line,
     * because that is what the console parses; the name is there for anything reading the stream
     * directly.
     */
    private void send(SseEmitter emitter, String type, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.putAll(payload);
        try {
            emitter.send(SseEmitter.event().name(type).data(event, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            throw new IllegalStateException("could not send the '" + type + "' event", e);
        }
    }
}
