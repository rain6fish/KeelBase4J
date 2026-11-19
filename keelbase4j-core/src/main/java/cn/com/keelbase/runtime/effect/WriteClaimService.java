// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.governance.ExecutionAxis;
import cn.com.keelbase.runtime.identity.Principal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Takes, settles and releases the pre-execution claim that makes two identical calls not both write.
 *
 * <p>See {@link WriteClaim} for why the arbitration has to be here rather than on the effect row. What
 * this class adds is the part that decides: <b>the insert is the arbitration</b>, and everything a loser
 * learns afterwards is only used to say <em>why</em> it lost.
 *
 * <p><b>A live claim is not retried; a lease that ran out is.</b> A row that reads {@code claimed} may be
 * an execution in flight or one that died silently, and from here those look the same — so while the
 * claim is fresh the answer is "do not execute", and guessing would mean occasionally running a second
 * copy of a write. What breaks the tie is time, and it has to be said in one place: past
 * {@code ExecutionAxis.LEASE_MILLIS} an attempt is not coming back, and the key is taken again. The
 * price of that door is named in {@link #reclaimOrLose}.
 *
 * <p><b>Nothing is released on a guess.</b> {@link #release} exists for an attempt that failed in a way
 * that <em>proves</em> nothing landed — for this runtime's tools, a refusal thrown by the row scope
 * before any write. A caller that cannot make that claim (a request that may have reached a remote
 * system) must leave the row {@code claimed}: releasing it is not tidying up, it is inviting the write
 * to happen twice.
 *
 * <p><b>A stranded hold is taken over, and the takeover is a row of the chain.</b> The lease door above
 * is the one place this runtime releases a hold whose conclusion stays unknown, and the contract makes
 * that act — not the hold — the record: {@code claim_release} on the same audit chain, with no
 * {@code outcome}, because no verdict was reached. The takeover never waits on the chain: the key has
 * already moved, so the row is written best-effort and a failed append is logged rather than turning a
 * successful takeover into a refused execution.
 *
 * <p>领取、落定与释放**执行前**的那条占位——正是它让两次相同调用不会都写。
 *
 * <p>为什么仲裁必须在这里、而不是在 effect 行上，见 {@link WriteClaim}。本类加的是**做决定**的那部分：
 * **插入即仲裁**，而输家事后读到的一切只用来解释它**为什么**输。
 *
 * <p>**活着的占位不重试；租约跑完的会。** 读到 `claimed` 的行，可能是一次正在飞的执行，也可能是一次死得
 * 无声无息的执行，而从这里看两者一样——所以在占位**新鲜**时答案是「不要执行」，靠猜意味着偶尔跑出第二次写。
 * 解开这个平局的是**时间**，而它必须只在一个地方说：过了 `ExecutionAxis.LEASE_MILLIS`，一次尝试就不会再
 * 回来了，键被重新取走。那扇门的代价写在 {@link #reclaimOrLose} 里。
 *
 * <p>**不靠猜就释放任何东西。** `release` 是为「以一种**证明**什么都没落地的方式失败」的尝试准备的——在本
 * 运行时的工具里，是行范围在任何写之前抛出的拒绝。做不出这个断言的调用方（请求可能已经到达远端系统）必须把行
 * 留在 `claimed`：释放它不是收拾，是**邀请这次写发生两次**。
 *
 * <p><b>一次滞留的占位被接管，而这次接管本身就是链上的一行。</b>上面的租约门是本运行时**唯一**释放「结论
 * 始终未知」的占位的地方，而契约要记的是**这个动作**、不是那份占位：同一条审计链上的 `claim_release`，
 * **不写** `outcome`，因为没有任何裁决发生。接管**从不**等这条链：键已经换了主人，故这一行是尽力而为写下的，
 * 一次失败的追加只会被记进日志，而不会把一次成功的接管变成一次被拒的执行。
 */
@Service
public class WriteClaimService {

    /**
     * @param status what the row read when the claim was refused; {@code null} when it could not be read
     * @param claimId the row that refused this call, so an operator can name it to release it; {@code
     *     null} on a win and when no row could be read
     *
     * 被拒时那一行读到的是什么；读不出来时为 {@code null}。`claimId` 是拒掉这次调用的那一行 ——
     * 运维据此点名释放它；赢了、以及一行都读不出来时为 {@code null}。
     */
    public record Outcome(boolean won, String status, Long claimId) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(WriteClaimService.class);

    private final WriteClaimRepository repository;

    /**
     * Asked for, not required. The audit bean is resolved lazily so that this service still works —
     * and still arbitrates — in a context that has no chain configured: the audit row is a record of
     * the takeover, never a precondition of it.
     *
     * 问一声、但不强求。审计 bean 惰性取用，好让本服务在没有配链的上下文里**照样能用、照样仲裁**：那一行
     * 是接管的**记录**，从来不是接管的**前提**。
     */
    private final ObjectProvider<AuditService> auditService;

    @Autowired
    public WriteClaimService(WriteClaimRepository repository, ObjectProvider<AuditService> auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /**
     * For a caller that constructs this service by hand — tests, mostly. No chain, so no row.
     *
     * 留给**手工**构造本服务的调用方 —— 主要是测试。没有链，就没有那一行。
     */
    public WriteClaimService(WriteClaimRepository repository) {
        this(repository, null);
    }

    /**
     * The key a claim and its effect share. They must agree: the claim arbitrates the write, the effect
     * records it, and a probe that looked one up by a key the other was not written under would answer
     * "this call is new" about a call that is not.
     */
    public static String keyFor(Principal principal, String toolName, String argsJson) {
        return SideEffectService.idempotencyKey(principal.userId(), toolName, argsJson);
    }

    /**
     * Claim this call for execution, outside any conversation — see
     * {@link #claim(Principal, String, String, String)}.
     *
     * 领取这次调用的执行权，不属于任何会话 —— 见 {@link #claim(Principal, String, String, String)}。
     */
    public Outcome claim(Principal principal, String toolName, String argsJson) {
        return claim(principal, toolName, argsJson, null);
    }

    /**
     * Claim this call for execution.
     *
     * <p>Deliberately not {@code @Transactional}, for the reason {@link SideEffectService#record} is not:
     * the insert has to be able to fail on the unique key and roll back <em>on its own</em>, so the
     * re-read that follows sees a fresh persistence context. Inside one transaction the constraint
     * violation poisons the context and the read-back is what breaks.
     *
     * <p>The conversation is carried only so a takeover has somewhere to file its {@code claim_release}
     * row; a call that belongs to none passes {@code null}, and the row then carries none — the same
     * honest answer the chain gives for every other row written outside a conversation.
     *
     * 领取这次调用的执行权。
     *
     * <p>**刻意不加** `@Transactional`，理由与 {@link SideEffectService#record} 相同：插入必须能在唯一键上
     * 失败并**自行**回滚，这样随后的重读才读到一个干净的持久化上下文。放进一个事务里，约束违例会把上下文毒掉，
     * 坏掉的恰恰是那次重读。
     *
     * <p>会话只是捎带，好让接管有地方挂它那一行 `claim_release`；不属于任何会话的调用传 `null`，那一行便不挂
     * 会话 —— 与链对其它「会话之外写下的行」给出的诚实的答案相同。
     */
    public Outcome claim(Principal principal, String toolName, String argsJson, String conversationId) {
        String key = keyFor(principal, toolName, argsJson);
        try {
            repository.saveAndFlush(new WriteClaim(key, principal.userId(), toolName));
            return new Outcome(true, WriteClaim.CLAIMED, null);
        } catch (DataIntegrityViolationException taken) {
            return reclaimOrLose(key, principal, conversationId);
        }
    }

    /**
     * The row already exists. Take it only if no attempt can still be running for it, and only if the
     * conditional update lands — another caller may be doing exactly this, and then neither of us may
     * proceed rather than both.
     *
     * <p>Two ways a row is free. A previous attempt <em>released</em> it, which is an attempt that said
     * nothing landed. Or it is still {@code claimed} but older than the lease, which is an attempt that
     * died between taking the claim and settling it — the process was killed, the machine went away, no
     * code of ours ran again to say so. Without that second door the key stays claimed forever and the
     * call can never be made again, which is a worse answer than a retry: a governance layer that turns
     * a crash into a permanently impossible action is not being careful, it is being stuck.
     *
     * <p><b>What the second door costs, said plainly.</b> A lease cannot tell a dead attempt from a very
     * slow one, and an attempt that died <em>after</em> the tool wrote but <em>before</em> the effect
     * was recorded leaves nothing for the ledger probe to find — so a retry may write a second time.
     * That is the same bargain the confirmation row's execution lease already makes, with the same
     * constant; the alternative is a key nobody can ever use again.
     *
     * <p><b>Fail closed.</b> A row that cannot be read is not evidence that the key is free, so the
     * answer is "do not execute" — never "I could not check, so go ahead".
     *
     * <p><b>A row an operator closed is free by neither door.</b> {@code closed_unknown} says a person
     * decided that nobody knows what became of the write; the conclusion did not become "it did not
     * land", so the lease must not silently turn that decision into a retry.
     *
     * <p>**读不出来就按关闭处理。** 一行读不出来不是「键空闲」的证据，故答案是「不要执行」——
     * 从不是「我查不了，那就上」。
     *
     * <p>**运维关掉的行，两扇门都进不去。** `closed_unknown` 说的是「有人决定了没人知道那次写怎么了」；
     * 结论并没有变成「它没落地」，故租约不得把那个决定悄悄变回一次重试。
     */
    private Outcome reclaimOrLose(String key, Principal taker, String conversationId) {
        WriteClaim existing = repository.findByIdempotencyKey(key).orElse(null);
        if (existing == null) {
            return new Outcome(false, null, null);
        }
        Instant now = Instant.now();
        boolean released = WriteClaim.RELEASED.equals(existing.getStatus());
        boolean stranded = WriteClaim.CLAIMED.equals(existing.getStatus()) && isStale(existing, now);
        if (!released && !stranded) {
            return new Outcome(false, existing.getStatus(), existing.getId());
        }
        int reclaimed = repository.reclaim(key, WriteClaim.RELEASED, WriteClaim.CLAIMED,
                WriteClaim.CLAIMED, now, now.minusMillis(ExecutionAxis.LEASE_MILLIS));
        if (reclaimed == 1) {
            if (stranded) {
                // Only the lease door leaves a row: the released door is an attempt that said nothing
                // landed, so its conclusion is known and there is no unknown to release. This branch is
                // the one the contract calls `claim_release` — a hold whose fate nobody knows changed
                // hands, and the act of taking it is the record.
                //
                // 只有租约门留行：释放门是一次「说了什么都没落地」的尝试，其结论是已知的，没有未知可释放。
                // 这一支才是契约说的 `claim_release` —— 一份**没人知道结局**的占位换了主人，而**接管这个动作**
                // 本身就是记录。
                recordRelease(existing, taker, conversationId);
            }
            return new Outcome(true, WriteClaim.CLAIMED, null);
        }
        WriteClaim after = repository.findByIdempotencyKey(key).orElse(null);
        return after == null
                ? new Outcome(false, null, null)
                : new Outcome(false, after.getStatus(), after.getId());
    }

    /**
     * Leaves the takeover as one row of the chain: {@code claim_release}, no {@code outcome}, and a
     * detail that names the hold, both owners and the reason.
     *
     * <p><b>Best-effort, and loudly so.</b> The key has already moved by the time this runs — turning a
     * lost append into a refused execution would refuse an execution that is entitled to run and would
     * not give the hold back. What a failed append must not do is pass silently, so it is logged with
     * both owners and the claim id.
     *
     * 把这次接管留成链上**一行**：`claim_release`、**不写** `outcome`，`detail` 点名那份占位、两任主人与原因。
     *
     * <p>**尽力而为，且失败必须出声。** 走到这里时键已经换了主人 —— 把一次失败的追加变成一次被拒的执行，
     * 既拒掉了一次**有权运行**的执行，也**换不回**那份占位。失败的追加唯一不许做的事是悄无声息，故它连同
     * 两任主人和占位 id 一起记进日志。
     */
    private void recordRelease(WriteClaim prior, Principal taker, String conversationId) {
        AuditService audit = auditService == null ? null : auditService.getIfAvailable();
        if (audit == null) {
            return;
        }
        String detail = "released stranded write claim #" + prior.getId()
                + " (" + prior.getToolName() + ", held by user " + prior.getUserId() + ")"
                + " → take-over by user " + taker.userId()
                + "; reason: the lease expired, so the previous attempt is not coming back"
                + (prior.getEffectId() == null ? "" : "; effect: " + prior.getEffectId());
        try {
            // No outcome: this row is not a tool verdict, and the hold's conclusion deliberately
            // stays unknown. The tool is passed so the row can be filtered by tool without parsing
            // `detail` — the same shape the reference implementation writes.
            //
            // 不写 outcome：这一行不是工具结局，占位的结论**有意**保持未知。工具名单独传入，好让这一行
            // 不必解析 `detail` 就能按工具筛选 —— 与参照实现写下的形状相同。
            audit.append("claim_release", taker.userId(), detail, false, null, prior.getToolName(),
                    conversationId);
        } catch (RuntimeException failed) {
            LOG.error("write claim #{} ({}) was taken over from user {} by user {}, but its "
                            + "claim_release row was not written — the chain does not say the hold "
                            + "changed hands: {}",
                    prior.getId(), prior.getToolName(), prior.getUserId(), taker.userId(),
                    failed.toString(), failed);
        }
    }

    /**
     * Whether a claim has outlived its lease — asked with the same constant, and about the same
     * question, as the confirmation row's execution claim ({@code ExecutionAxis}). A fresh claim means
     * an attempt may be running right now; an old one means it is not coming back.
     */
    private static boolean isStale(WriteClaim claim, Instant now) {
        return now.toEpochMilli() - claim.getClaimedAt().toEpochMilli() >= ExecutionAxis.LEASE_MILLIS;
    }

    /** The execution finished; {@code effectId} is the effect it produced, or {@code null} if none did. */
    public void settle(Principal principal, String toolName, String argsJson, Long effectId) {
        repository.settle(keyFor(principal, toolName, argsJson), WriteClaim.CLAIMED, WriteClaim.SETTLED,
                effectId, Instant.now());
    }

    /** The attempt failed without landing, so the key is free for a retry. See the class javadoc. */
    public void release(Principal principal, String toolName, String argsJson) {
        repository.release(keyFor(principal, toolName, argsJson), WriteClaim.CLAIMED, WriteClaim.RELEASED);
    }
}
