// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import cn.com.keelbase.runtime.identity.Principal;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Takes, settles and releases the pre-execution claim that makes two identical calls not both write.
 *
 * <p>See {@link WriteClaim} for why the arbitration has to be here rather than on the effect row. What
 * this class adds is the part that decides: <b>the insert is the arbitration</b>, and everything a loser
 * learns afterwards is only used to say <em>why</em> it lost.
 *
 * <p><b>The loser is not told to retry.</b> A row that reads {@code claimed} may be an execution in
 * flight or one that died silently, and those two are indistinguishable from here — so the answer is the
 * same for both, and it is "do not execute". Guessing would mean occasionally running a second copy of
 * a write, which is the thing this row exists to prevent.
 *
 * <p><b>Nothing is released on a guess.</b> {@link #release} exists for an attempt that failed in a way
 * that <em>proves</em> nothing landed — for this runtime's tools, a refusal thrown by the row scope
 * before any write. A caller that cannot make that claim (a request that may have reached a remote
 * system) must leave the row {@code claimed}: releasing it is not tidying up, it is inviting the write
 * to happen twice.
 *
 * <p>领取、落定与释放**执行前**的那条占位——正是它让两次相同调用不会都写。
 *
 * <p>为什么仲裁必须在这里、而不是在 effect 行上，见 {@link WriteClaim}。本类加的是**做决定**的那部分：
 * **插入即仲裁**，而输家事后读到的一切只用来解释它**为什么**输。
 *
 * <p>**输家不会被告知「重试」。** 读到 `claimed` 的行，可能是一次正在飞的执行，也可能是一次死得无声无息的
 * 执行，而从这里**分不出**是哪一种——所以两者的答案相同，都是「不要执行」。靠猜，意味着偶尔会跑出第二次写，
 * 而那正是这一行存在的理由。
 *
 * <p>**不靠猜就释放任何东西。** `release` 是为「以一种**证明**什么都没落地的方式失败」的尝试准备的——在本
 * 运行时的工具里，是行范围在任何写之前抛出的拒绝。做不出这个断言的调用方（请求可能已经到达远端系统）必须把行
 * 留在 `claimed`：释放它不是收拾，是**邀请这次写发生两次**。
 */
@Service
public class WriteClaimService {

    /** @param status what the row read when the claim was refused; {@code null} when it could not be read */
    public record Outcome(boolean won, String status) {
    }

    private final WriteClaimRepository repository;

    public WriteClaimService(WriteClaimRepository repository) {
        this.repository = repository;
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
     * Claim this call for execution.
     *
     * <p>Deliberately not {@code @Transactional}, for the reason {@link SideEffectService#record} is not:
     * the insert has to be able to fail on the unique key and roll back <em>on its own</em>, so the
     * re-read below sees a fresh persistence context. Inside one transaction the constraint violation
     * poisons the context and the read-back is what breaks.
     */
    public Outcome claim(Principal principal, String toolName, String argsJson) {
        String key = keyFor(principal, toolName, argsJson);
        try {
            repository.saveAndFlush(new WriteClaim(key, principal.userId(), toolName));
            return new Outcome(true, WriteClaim.CLAIMED);
        } catch (DataIntegrityViolationException taken) {
            return reclaimOrLose(key);
        }
    }

    /**
     * The row already exists. Take it only if a previous attempt <em>released</em> it, and only if the
     * conditional update lands — another caller may be doing exactly this, and then neither of us may
     * proceed rather than both.
     *
     * <p><b>Fail closed.</b> A row that cannot be read is not evidence that the key is free, so the
     * answer is "do not execute" — never "I could not check, so go ahead".
     */
    private Outcome reclaimOrLose(String key) {
        WriteClaim existing = repository.findByIdempotencyKey(key).orElse(null);
        if (existing == null) {
            return new Outcome(false, null);
        }
        if (!WriteClaim.RELEASED.equals(existing.getStatus())) {
            return new Outcome(false, existing.getStatus());
        }
        int reclaimed = repository.reclaim(key, WriteClaim.RELEASED, WriteClaim.CLAIMED, Instant.now());
        if (reclaimed == 1) {
            return new Outcome(true, WriteClaim.CLAIMED);
        }
        return new Outcome(false, repository.findByIdempotencyKey(key)
                .map(WriteClaim::getStatus).orElse(null));
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
