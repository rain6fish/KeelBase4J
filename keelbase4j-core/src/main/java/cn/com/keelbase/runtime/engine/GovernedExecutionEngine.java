// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.engine;

import cn.com.keelbase.protocol.CanonicalJson;
import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.effect.WriteClaimService;
import cn.com.keelbase.runtime.governance.ConfirmationMode;
import cn.com.keelbase.runtime.governance.ConfirmationRequest;
import cn.com.keelbase.runtime.governance.ConfirmationStore;
import cn.com.keelbase.runtime.governance.ConfirmationWatchers;
import cn.com.keelbase.runtime.governance.ExecutionAxis;
import cn.com.keelbase.runtime.governance.GateDecision;
import cn.com.keelbase.runtime.governance.GovernanceService;
import cn.com.keelbase.runtime.identity.OperatorIdentity;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The trust loop. Every AI tool call goes through here — there is no path that executes a tool
 * while bypassing the gate, the audit, or the side-effect ledger.
 *
 * <pre>
 *   gate(risk) ─┬─ BLOCK             → audit, never execute
 *               ├─ REQUIRE_APPROVAL  → issue a token, wait for a *second person* (R4)
 *               ├─ CONFIRM           → issue a token, wait for the operator (R3)
 *               └─ ALLOW             → audit, execute, record side effect
 * </pre>
 *
 * <p>**信任闭环**。每一次 AI 工具调用都从这里过——没有哪条路径能绕过门控、审计或副作用账本去执行工具。
 *
 * <p>门控的四个出口：BLOCK 只审计、永不执行；**REQUIRE_APPROVAL 发一个 token 等第二个人**（R4）；
 * CONFIRM 发一个 token 等操作者本人（R3）；ALLOW 审计后执行并登记副作用。前两者的区别**只在于谁可以
 * 回答那张 token**——而这一点由行的 mode 记住，不由这条分支临时判断。
 */
@Service
public class GovernedExecutionEngine {

    private final ToolRegistry registry;
    private final GovernanceService governance;
    private final ConfirmationStore confirmations;
    private final SideEffectService sideEffects;
    private final WriteClaimService claims;
    private final AuditService audit;
    private final ConfirmationWatchers watchers;

    public GovernedExecutionEngine(
            ToolRegistry registry,
            GovernanceService governance,
            ConfirmationStore confirmations,
            SideEffectService sideEffects,
            WriteClaimService claims,
            AuditService audit,
            ConfirmationWatchers watchers) {
        this.registry = registry;
        this.governance = governance;
        this.confirmations = confirmations;
        this.sideEffects = sideEffects;
        this.claims = claims;
        this.audit = audit;
        this.watchers = watchers;
    }

    /** Gate and (if allowed) execute a tool call. */
    public ExecutionOutcome execute(String toolName, Map<String, Object> args, Principal principal) {
        AiTool tool = registry.require(toolName);
        GateDecision decision = governance.decide(tool, principal);
        switch (decision) {
            case BLOCK:
                audit.append("tool_call", principal.userId(), toolName + " blocked (risk policy)");
                return new ExecutionOutcome("blocked", governance.reasons(tool).toWire(), null, null,
                        "blocked by risk policy");
            case REQUIRE_APPROVAL: {
                // A high-impact action waits for a second person, so the row is written and its token
                // returned; nothing runs until somebody else answers it (ADR-0018). Until this branch
                // wrote a row it stopped at an audit line, and a token nobody held could not be
                // answered by anyone.
                //
                // 高影响动作要等**第二个人**，所以这里写下那一行、把 token 交回去；在别人回答它之前什么都
                // 不跑（ADR-0018）。在此之前这条分支只留一行审计就返回，没有人持有 token，也就没有人能回答它。
                ConfirmationRequest req = confirmations.create(
                        principal, toolName, CanonicalJson.json(args), tool.riskLevel(),
                        ConfirmationMode.APPROVAL);
                audit.append("tool_call", principal.userId(), toolName + " awaiting a second person");
                return new ExecutionOutcome("requires_approval", null, req.getToken(), null, null);
            }
            case CONFIRM: {
                ConfirmationRequest req = confirmations.create(
                        principal, toolName, CanonicalJson.json(args), tool.riskLevel(),
                        ConfirmationMode.IMMEDIATE);
                audit.append("tool_call", principal.userId(), toolName + " pending confirmation");
                return new ExecutionOutcome("pending_confirmation", null, req.getToken(), null, null);
            }
            default:
                return run(tool, args, principal);
        }
    }

    /**
     * Approve a pending confirmation and perform the write.
     *
     * <p><b>The claim comes first, and it is what makes the write single.</b> Claiming takes the row
     * out of {@code pending} atomically, so a second approval arriving at the same instant loses the
     * claim and never reaches the tool. The order that reads more naturally — check it is pending,
     * execute, then record — is the order that lets two winners through, because both check before
     * either records.
     */
    public ExecutionOutcome approve(String token, Principal principal) {
        return performApproval(confirmations.claim(token, principal, ConfirmationLifecycle.APPROVED),
                principal, ConfirmationLifecycle.IN_BAND);
    }

    /** Decline a pending confirmation — nothing is written. */
    public ExecutionOutcome decline(String token, Principal principal) {
        return performDecline(confirmations.claim(token, principal, ConfirmationLifecycle.DECLINED),
                principal, ConfirmationLifecycle.IN_BAND);
    }

    /**
     * Decide this operator's confirmation from outside the conversation — the Action Center, after the
     * dialogue has been left (ADR-0015).
     *
     * <p>Same transitions as the in-band path and the same single execution, because the claim still
     * comes first; what differs is the guards, which are the frozen lifecycle's out-of-band ones, and
     * the fact that a lost claim is reported as a no-op instead of raised as a conflict.
     */
    public OutOfBandDecision decideOutOfBand(String token, Principal principal, String decision) {
        ConfirmationStore.OutOfBandResult result = confirmations.decideOutOfBand(token, principal, decision,
                Instant.now(), ConfirmationLifecycle.DEFAULT_OFFLINE_TTL_MILLIS);
        return switch (result.outcome()) {
            case NOT_FOUND -> new OutOfBandDecision(false, null, null, "not found");
            case NOT_THIS_PATHS_ROW -> new OutOfBandDecision(false, null, null,
                    "only the operator's own immediate confirmations can be decided here");
            case ALREADY_DECIDED -> new OutOfBandDecision(false, null, null, "already decided");
            case EXPIRED -> new OutOfBandDecision(false, null, null, "the offline window has closed");
            case DECIDED -> {
                ExecutionOutcome outcome = ConfirmationLifecycle.APPROVE.equals(decision)
                        ? performApproval(result.request(), principal, ConfirmationLifecycle.OUT_OF_BAND)
                        : performDecline(result.request(), principal, ConfirmationLifecycle.OUT_OF_BAND);
                yield new OutOfBandDecision(true, "executed".equals(outcome.status()), outcome.effectId(),
                        outcome.error());
            }
        };
    }

    /**
     * Answer an approval-mode confirmation — a <em>second person</em> deciding a high-impact action
     * (ADR-0018).
     *
     * <p>Both guards live in the store, next to the conditional update that makes an answer single:
     * the row has to be an approval row, and the caller may not be its initiator. The write then runs
     * as the initiator, not as the approver.
     *
     * <p>**回答一条审批确认**——由**第二个人**裁决高影响动作（ADR-0018）。
     *
     * <p>两道守卫都在 store 里，就挨着那条让「回答只有一次」的条件更新：这一行必须是审批行，且调用者不能
     * 是它的发起人。随后这次写以**发起人**身份执行，而不是以审批人身份。
     */
    public ExecutionOutcome decideApproval(String token, Principal approver, String decision) {
        String toStatus = ConfirmationLifecycle.APPROVE.equals(decision)
                ? ConfirmationLifecycle.APPROVED
                : ConfirmationLifecycle.DECLINED;
        ConfirmationRequest req = confirmations.claimApproval(token, approver, toStatus);
        return ConfirmationLifecycle.APPROVED.equals(toStatus)
                ? performApproval(req, approver, ConfirmationLifecycle.OUT_OF_BAND)
                : performDecline(req, approver, ConfirmationLifecycle.OUT_OF_BAND);
    }

    /**
     * Run an approved-but-not-succeeded confirmation again (ADR-0018).
     *
     * <p>What it is for: an attempt that died without recording a result — the process was killed, the
     * container restarted, an external call hung. The row is left {@code approved} with a stale claim
     * and the tool never ran, and until now there was no way to ask for it a second time at all.
     *
     * <p>Refused when the token is unknown or is not an approval row, when the row is not
     * {@code approved}, when it already succeeded, and when its claim is still fresh — that last one
     * is what keeps a retry from racing a live attempt. Running it twice over is safe because the
     * write pipeline is idempotent: a retry does not produce a second side effect.
     *
     * <p>**把一条「已批准但未成功」的确认再跑一次**（ADR-0018）。
     *
     * <p>它为什么存在：一次**没记下结果就死了**的尝试——进程被杀、容器重启、外部调用挂起。那一行会停在
     * {@code approved}、带着一个过期的认领，而工具从未跑过；在此之前，根本没有任何办法再问它一次。
     *
     * <p>以下情形拒绝：token 未知或不是审批行、行不是 {@code approved}、它已经成功、以及它的认领**仍新鲜**
     * ——最后这一条正是**不让重试与在途尝试撞车**的那一条。重跑本身是安全的，因为写管道幂等：重试不会产出
     * 第二个副作用。
     */
    public ExecutionOutcome retryExecution(String token, Principal retriedBy) {
        ConfirmationRequest req = confirmations.find(token);
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown confirmation token");
        }
        if (!ConfirmationMode.APPROVAL.equals(req.getMode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "only an approval confirmation has an execution that can be retried");
        }
        if (req.getExecutedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this confirmation already executed");
        }
        if (!ConfirmationLifecycle.APPROVED.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "cannot retry a " + req.getStatus() + " confirmation");
        }
        if (!ExecutionAxis.isClaimable(req, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "an execution attempt is still running");
        }
        audit.append("tool_confirmation", retriedBy.userId(), req.getToolName() + " execution retried");
        return executeApproved(req, initiator(req, retriedBy));
    }

    /**
     * The approval tail, shared by every decision path: audit, execute, record the effect, then tell
     * whoever is watching.
     *
     * <p>{@code via} is recorded in the audit because the frozen lifecycle says a decision carries
     * where it was taken; the state machine does not fork on it, and neither does the wire. The frozen
     * vocabulary is {@code in_band} / {@code out_of_band} and its transitions are all taken by the
     * owner, so a second person's decision reuses {@code out_of_band} rather than inventing a word: it
     * genuinely is a decision taken outside the dialogue, and who took it is recorded as the audit's
     * subject and the row's {@code approver_id}.
     *
     * <p>**审批的尾段**，每条裁决路径共用：审计、执行、登记 effect，然后告诉正在看的人。
     *
     * <p>{@code via} 进审计，因为冻结的生命周期说一次裁决要携带**它在哪里被作出**；状态机不为它分叉，
     * 线缆也不分。冻结的词汇是 {@code in_band} / {@code out_of_band}，且它的转移**全部由 owner 做**，所以
     * 第二个人这次的裁决复用 {@code out_of_band} 而不是自己造一个词：它**确实**是在对话之外作出的裁决，
     * 而**是谁**作出的，记在审计行的人身上和该行的 {@code approver_id} 里。
     */
    private ExecutionOutcome performApproval(ConfirmationRequest req, Principal decider, String via) {
        AiTool tool = registry.require(req.getToolName());
        audit.append("tool_confirmation", decider.userId(), tool.name() + " approved (" + via + ")");
        return executeApproved(req, initiator(req, decider));
    }

    /**
     * The execution tail: claim the attempt, run the tool, settle the row, then tell whoever is
     * watching.
     *
     * <p><b>The claim is a conditional update</b> (ADR-0018). On the ordinary approval path only one
     * caller can be here — the row was already taken out of {@code pending} — but a retry is a second
     * entry into this tail, and a claim that is read and then written lets a retry and a live attempt
     * both pass. The condition is what makes "at most one attempt" hold, and it is also what refuses
     * a row that has already succeeded.
     *
     * <p>**执行的尾段**：认领这次尝试、跑工具、落这一行的结果，然后告诉正在看的人。
     *
     * <p>**认领是条件更新**（ADR-0018）。在普通的审批路径上这里只可能有一个调用方——行早就被移出
     * {@code pending} 了——但**重试是这段尾巴的第二个入口**，而「先读后写」的认领会让重试与在途尝试
     * **都通过**。条件才是让「至多一次尝试」成立的东西，也是拒绝一条**已成功**的行的东西。
     */
    private ExecutionOutcome executeApproved(ConfirmationRequest req, Principal initiator) {
        AiTool tool = registry.require(req.getToolName());
        // The row then records that an attempt took it, so an attempt that never reports back reads as
        // such instead of as "approved, nothing happened". The claim is the lease — an attempt the
        // lease has run out on derives `failed`, and may be taken again.
        Instant claimedAt = Instant.now();
        if (!confirmations.claimExecution(req.getToken(), claimedAt, ExecutionAxis.LEASE_MILLIS)) {
            return new ExecutionOutcome("error", null, null, null,
                    "execution already in progress or already completed");
        }
        // The row now carries the claim; this copy has to as well, or the save below writes the
        // pre-claim null back over it and an attempt in flight becomes invisible.
        //
        // 数据库那一行现在已经带上认领了；手上这份也必须带上，否则下面的 save 会把「认领之前」的 null
        // 写回去，一次正在进行的尝试就此变得不可见。
        req.setExecutionClaimedAt(claimedAt);
        ExecutionOutcome outcome;
        try {
            outcome = run(tool, parseArgs(req.getArgsJson()), initiator);
        } catch (RuntimeException failed) {
            // The tool threw instead of answering. The attempt is recorded as failed and keeps its
            // claim on purpose: clearing it would say the attempt never happened, and a claim with no
            // result is exactly what the lease turns into `failed`.
            req.setExecutionError(message(failed));
            confirmations.save(req);
            throw failed;
        }
        if ("executed".equals(outcome.status())) {
            req.setExecutedAt(Instant.now());
            req.setExecutionError(null);
        } else {
            req.setExecutionError(outcome.error() == null ? "execution failed" : outcome.error());
        }
        req.setResultId(outcome.effectId());
        confirmations.save(req);
        // Last, and after the row is durable: whoever is watching the stream is told once the decision
        // is a fact, not before. An out-of-band decision reaches an open stream the same way — the
        // stream reports events, it does not execute, so telling it cannot run the tool twice.
        watchers.decided(req.getToken(), decision(ConfirmationLifecycle.APPROVE, req.getToolName(), outcome));
        return outcome;
    }

    /**
     * Who the write belongs to: the identity the row was raised under, falling back to the decider for
     * a row written before the column existed.
     *
     * <p>Reading it off the row is what lets a second person approve at all — the approver is not the
     * person whose business action this is. It also means the write runs under the identity the request
     * was raised with, rather than whatever role that person happens to hold by the time it is answered
     * (ADR-0018).
     *
     * <p>**这次写归谁**：行签发时所带的那个身份；行写在这列存在之前时，回落到裁决者。
     *
     * <p>从行里读出来，正是「第二个人可以批准」得以成立的原因——审批人不是这桩业务动作的主人。它同时
     * 意味着写跑在**请求签发时**的身份下，而不是那个人在**被回答时**恰好持有的角色下（ADR-0018）。
     */
    private static Principal initiator(ConfirmationRequest req, Principal fallback) {
        Principal stored = OperatorIdentity.fromWireJson(req.getOperatorIdentity());
        return stored != null ? stored : fallback;
    }

    private static String message(RuntimeException failed) {
        return failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
    }

    /** The decline tail. Nothing is written, and whoever is watching is told the same way. */
    private ExecutionOutcome performDecline(ConfirmationRequest req, Principal principal, String via) {
        audit.append("tool_confirmation", principal.userId(), req.getToolName() + " declined (" + via + ")");
        ExecutionOutcome outcome =
                new ExecutionOutcome(ConfirmationLifecycle.DECLINED, null, null, null, null);
        watchers.decided(req.getToken(), decision(ConfirmationLifecycle.DECLINE, req.getToolName(), outcome));
        return outcome;
    }

    /**
     * What to tell a waiting stream. Shaped like the reference's {@code AiConfirmationDecision}: the
     * decision word, whether it approved, whether the tool ran, and the result if it did.
     */
    private Map<String, Object> decision(String decision, String toolName, ExecutionOutcome outcome) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("toolName", toolName);
        d.put("decision", decision);
        d.put("approved", "approve".equals(decision));
        d.put("success", "executed".equals(outcome.status()));
        if (outcome.effectId() != null) {
            d.put("resultId", outcome.effectId());
        }
        if (outcome.error() != null) {
            d.put("error", outcome.error());
        }
        return d;
    }

    /**
     * Execute an allowed tool, and audit it — including the case where it refuses.
     *
     * <p><b>The refusal is an event, so it gets a line.</b> The audit below is composed from the
     * outcome, which means it can only be written once an outcome exists. A refusal arrives as an
     * exception — the row scope rejecting a row that is not the caller's, for instance — and control
     * leaves before that line is reached. The effect is that the audit reports what succeeded and
     * never what was attempted and refused, which is the half an operator asks about first: not "what
     * did the AI do" but "did anything reach for what it should not". Measured before it was reasoned
     * about: an out-of-scope read left no audit row at all.
     *
     * <p>The catch is deliberately narrow in what it changes and broad in what it catches. It adds a
     * line and rethrows — the call is still refused, it is only no longer silent — and it catches
     * every {@code RuntimeException} rather than filtering for the type that happens to be thrown
     * today, because a filter would leave the next kind of refusal silently unrecorded, which is the
     * defect it exists to fix. A crash inside a tool is also an attempt worth seeing.
     *
     * <p>Nothing here can roll the line back: no transaction surrounds the engine call, so the audit
     * service's own transaction commits on its own.
     *
     * 执行一个被允许的工具，并审计它——**包括它拒绝的时候**。
     *
     * <p><b>拒绝本身就是一个事件，所以它要有一行。</b>下面那次审计是**由结果拼出来的**，也就是说只有
     * 结果存在时才写得出来。拒绝以异常到来——比如行范围驳回了一行不属于调用方的数据——控制流在到达那
     * 一行之前就离开了。后果是：审计报告「**做成了什么**」，而不报告「**尝试了什么、被拒了**」，而后者
     * 恰恰是运维第一个会问的那一半：不是「AI 做了什么」，而是「**有没有什么东西伸手去够了它不该够的**」。
     * **先测到、后推理**：一次越界读没有留下任何审计行。
     *
     * <p>这个 catch 在「改什么」上有意收窄、在「捕什么」上有意放宽。它**只加一行、再原样抛出**——调用
     * 仍然被拒，只是不再无声——并且捕**所有** {@code RuntimeException}，而不是按今天恰好抛出的那个类型
     * 过滤；因为过滤会让**下一种**拒绝继续被静默漏记，而那正是它要修的缺陷。工具内部的崩溃同样是一次
     * 值得被看见的尝试。
     *
     * <p>这里写下的行**不可能被回滚**：引擎调用外面没有事务，审计服务自己的事务独立提交。
     */
    private ExecutionOutcome run(AiTool tool, Map<String, Object> args, Principal principal) {
        String argsJson = CanonicalJson.json(args);
        ToolResult result;
        boolean claimed = false;
        try {
            ExecutionOutcome reused = reuseIfAlreadyExecuted(tool, args, principal);
            if (reused != null) {
                return reused;
            }
            claimed = claimForExecution(tool, argsJson, principal);
            result = tool.execute(args, principal); // may throw 403 — before any write
        } catch (RuntimeException refused) {
            if (claimed) {
                claims.release(principal, tool.name(), argsJson);
            }
            audit.append("tool_call", principal.userId(),
                    tool.name() + " refused: " + refused.getMessage());
            throw refused;
        }
        Long effectId = null;
        if (result.success() && tool.resultType() != null) {
            Long resultId = resultId(result.data());
            SideEffect effect = sideEffects.record(
                    principal, tool.name(), tool.resultType(), resultId, argsJson, tool.revokeClass());
            effectId = effect.getId();
        }
        if (claimed) {
            claims.settle(principal, tool.name(), argsJson, effectId);
        }
        audit.append("tool_call", principal.userId(),
                tool.name() + " -> " + (result.success() ? "ok" : "fail: " + result.error()));
        return result.success()
                ? ExecutionOutcome.executed(result.data(), effectId)
                : new ExecutionOutcome("error", null, null, null, result.error());
    }

    /**
     * Take the pre-execution claim, or refuse the call — the half of S11 that a sequential repeat cannot
     * show.
     *
     * <p>Asking the ledger first (see {@link #reuseIfAlreadyExecuted}) settles every repeat that arrives
     * after the first one finished. It settles nothing about two calls that arrive together: both ask,
     * both find nothing, both write. The claim is the row whose unique key is taken <em>before</em> the
     * write, so exactly one of them owns the execution and the other is refused — which is the honest
     * answer, since from here an execution in flight and one that died silently look the same (see
     * {@code WriteClaimService}).
     *
     * <p>Not to be confused with the confirmation row's own claim ({@code executionClaimedAt},
     * ADR-0016): that one arbitrates <em>attempts at one confirmation</em>, this one arbitrates
     * <em>the same call arriving twice</em>. Two different questions, and a call can be the only attempt
     * at its confirmation and still be the second one of its kind.
     *
     * <p>Reads are not claimed: they write nothing, so there is nothing to arbitrate, and a row per read
     * would be a ledger of nothing.
     *
     * <p>领取执行前的占位，否则拒绝这次调用——S11 中**顺序重复显不出来**的那一半。
     *
     * <p>先问账本（见 {@link #reuseIfAlreadyExecuted}）能解决**第一次已经完成之后**到达的每一次重复；它对
     * **同时到达**的两次调用一无所用：两边都问、都没查到、都写。本方法取的那一行，唯一键是在写**之前**拿的，
     * 于是其中之一拥有这次执行、另一个被拒——这是诚实的答案，因为从这里看，**在飞的执行**与**死得无声无息的
     * 执行**长得一样（见 `WriteClaimService`）。
     *
     * <p>不要与确认行自己的认领（`executionClaimedAt`，ADR-0016）混为一谈：那个仲裁的是**针对同一条确认的
     * 多次尝试**，这个仲裁的是**同一次调用到了两遍**。两个不同的问题，而一次调用完全可以是它那条确认的唯一
     * 一次尝试、同时又是同类中的第二次。
     *
     * <p>读不占位：它什么都不写，也就没什么可仲裁；而每次读留一行，等于给「什么都没有」记账。
     */
    private boolean claimForExecution(AiTool tool, String argsJson, Principal principal) {
        if (tool.resultType() == null) {
            return false;
        }
        WriteClaimService.Outcome claim = claims.claim(principal, tool.name(), argsJson);
        if (!claim.won()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "this call is already being executed (claim: " + claim.status() + ")"
                            + " — the same call is not run twice");
        }
        return true;
    }

    /**
     * The effect this exact call already produced, if it did — asked <b>before</b> the tool runs.
     *
     * <p><b>Why before, and why it is a fix rather than an optimisation.</b> The ledger's idempotency
     * key is content-derived, and its javadoc promises that "re-running the same call reuses the
     * existing effect instead of writing twice". The write did not honour that: the tool wrote a new
     * row every time and the ledger then deduped, so the second identical call left a row <em>named by
     * no effect</em> — a write with no revocation path, invisible to the ledger and unreachable by
     * {@code revoke}. Measured on a host (seam S11), and only by a rerun: a single pass writes each
     * distinct call once. Asking first is what makes the promise true instead of merely stated.
     *
     * <p>A revoked effect keeps its key, and the call is refused rather than quietly remade under it —
     * the same answer the reference implementation gives (its write pipeline probes by key and returns
     * early, and a revoked effect still occupies the key). Making the same thing again is a different
     * request and needs different arguments; pretending otherwise would either write an untracked row
     * or silently do nothing.
     *
     * <p><b>What this does not cover, and the reference does:</b> two identical calls <em>at the same
     * instant</em> can both probe and miss and both write, leaving the loser's row untracked again.
     * The reference arbitrates that with a write claim taken before execution; this runtime has no such
     * row, so the concurrent case remains open and is recorded as such rather than claimed.
     */
    private ExecutionOutcome reuseIfAlreadyExecuted(AiTool tool, Map<String, Object> args,
                                                   Principal principal) {
        SideEffect existing = sideEffects
                .existingFor(principal, tool.name(), CanonicalJson.json(args))
                .orElse(null);
        if (existing == null) {
            return null;
        }
        if ("revoked".equals(existing.getRevokeStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "this call was revoked (effect " + existing.getId() + ") and its key stays occupied"
                            + " — change the arguments to make it again");
        }
        audit.append("tool_call", principal.userId(),
                tool.name() + " -> ok (effect " + existing.getId() + " reused: the same call)");
        return ExecutionOutcome.executed(
                existing.getResultId() == null ? null : Map.of("id", existing.getResultId()),
                existing.getId());
    }

    private static Long resultId(Object data) {
        if (data instanceof Map<?, ?> m && m.get("id") instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArgs(String argsJson) {
        return (Map<String, Object>) cn.com.keelbase.protocol.Json.parse(argsJson);
    }
}
