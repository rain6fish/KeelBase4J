// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.authz.GovernanceReadSubjects;
import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.governance.ExecutionAxis;
import cn.com.keelbase.runtime.identity.Principal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Releases a stranded write hold whose outcome nobody knows, and records the act once.
 *
 * <p>A stranded hold is one that reads {@code claimed} and has outlived its lease: the attempt either
 * died silently or is not coming back, and {@link WriteClaimService}'s reclaim door lets the next
 * identical call take the key. What that door cannot do is say <em>that it happened</em> — the
 * takeover is silent, so "this hold was given up on" and "this hold is still in flight" look the same
 * on the chain (neither leaves a row). This class is the operator's half: it closes the hold as
 * unknown and leaves one row on the chain saying so.
 *
 * <p><b>Release is not a verdict.</b> The write may have landed with its effect never recorded, or it
 * may never have happened; claiming either would be a guess. So the row carries no {@code outcome},
 * and the key stays occupied: the same call is refused from then on, because "we do not know" must
 * not become "try again". That is what separates this from {@link WriteClaim#RELEASED}, which frees
 * the key because an attempt proved that nothing landed.
 *
 * <p>The threshold is the lease in {@link ExecutionAxis} — the same constant that decides when an
 * attempt is not coming back, so there is one notion and one value. A second "staleness" knob would
 * be a second definition of the same question.
 *
 * <p><b>The close and its row commit together.</b> The endpoint used to commit the status change and
 * then append the chain row in a second transaction, so an append that failed left the ledger saying
 * the hold was closed while the chain said nothing had happened — and a retry was refused as
 * {@code already_closed}, so the missing row could never be written. Both steps now run in one
 * transaction: a failed append rolls the status change back, the caller is told the release did not
 * happen, and repeating it is the repair.
 *
 * <p><b>The row is also the tenant boundary.</b> Holding {@code update} on the ledger is necessary but
 * not sufficient: the caller may only close a hold it could reach as a row. {@link WriteClaim} carries
 * its owner, so the same row gate every other scoped entity uses
 * ({@link OwnershipGuard#requireAccess}) answers "is this hold yours" — an administrator's range is
 * everyone's, a tier-B grant scoped to its owner is its own.
 *
 * <p>A deployment whose audit granularity is {@code off} writes no row, by policy; the release still
 * happens and the answer's {@code auditRowId} is null. That absence is the policy, not a failure.
 *
 * <p><b>关闭与它那一行一起提交。</b>这个端点此前先提交状态、再以另一个事务追加链行，于是追加失败会让账本说
 * 占位已关、而链上说什么都没发生 —— 重试又被以 `already_closed` 拒绝，那一行永远补不上。如今两步同处一个事务：
 * 追加失败会把状态改动一并回滚，调用方被告知释放没有发生，而**重试就是修复**。
 *
 * <p><b>这一行同时也是租户边界。</b>在账本上持有 `update` 是**必要而不充分**的：调用方只能关闭它**够得到**
 * 的那一份占位。{@link WriteClaim} 带着它的主人，故与其它所有有范围实体同一道行闸
 * （{@link OwnershipGuard#requireAccess}）来回答「这份占位是不是你的」—— 管理员的范围是所有人，
 * 档 B 中按主人收窄的授予只含本人。
 *
 * 释放一份结局无人知晓的滞留写占位，并把这次动作记录一次。
 *
 * <p>滞留占位 = 读作 `claimed` 且已越过租约：那次尝试要么死得无声无息、要么不会回来了，而
 * {@link WriteClaimService} 的重新占位之门让下一个相同调用把键拿走。那扇门做不到的是**说出这件事发生过**
 * —— 接管是无声的，于是「这份占位被放弃了」与「它还在飞」在链上看起来一样（两种情况都不留行）。本类是
 * **运维那一半**：它把占位关闭为未知，并在链上留下一行说明。
 *
 * <p>**释放不是判决。** 那次写可能落了地而 effect 从未记录，也可能从未发生；断言任何一边都是猜。故这一行
 * **不带 `outcome`**，而键仍被占住：从那以后同一次调用被拒，因为「我们不知道」不能变成「再试一次」。这正是
 * 它与 {@link WriteClaim#RELEASED} 的区别 —— 那一种放走键，是因为一次尝试证明了什么都没落地。
 *
 * <p>阈值就是 {@link ExecutionAxis} 里的租约 —— 与「一次尝试不会再回来」同一个常量，故只有一个概念、一个
 * 取值。再开一个「陈旧」旋钮，等于给同一个问题写第二个定义。
 *
 * <p>审计粒度为 `off` 的部署按策略不写行；释放照常发生，而答复里的 `auditRowId` 为 null。那个缺席是策略，
 * 不是失败。
 */
@Service
public class WriteClaimReleaseService {

    /**
     * The shortest note accepted. The row exists to say <em>why</em> a hold was given up on, and a
     * blank or one-word note does not answer it; the bound is the reference's own, kept so both
     * runtimes refuse the same input.
     *
     * 接受的最短说明。这一行的存在意义是说出**为什么**放弃一份占位，空话或一个词答不了它；这个下界取自
     * 参照实现，好让两个运行时拒绝同一批输入。
     */
    private static final int MIN_NOTE = 8;

    /** A bound that keeps the note inside the audit row's own column instead of failing mid-append. */
    private static final int MAX_NOTE = 1000;

    /** Same reason as {@link #MAX_NOTE}, for the evidence reference. */
    private static final int MAX_EVIDENCE = 400;

    /** What a successful release answers. */
    public record Released(Long claimId, String toolName, String status, Long auditRowId) {
    }

    private final WriteClaimRepository claims;
    private final AuditService audit;
    private final OwnershipGuard guard;

    public WriteClaimReleaseService(WriteClaimRepository claims, AuditService audit,
                                    OwnershipGuard guard) {
        this.claims = claims;
        this.audit = audit;
        this.guard = guard;
    }

    /**
     * Close {@code claimId} as unknown and put one row on the chain — in one transaction.
     *
     * <p>Refusals are kinds, not a bare {@code false}: {@code not_found} (no such row),
     * {@code not_claimable} (the execution settled or was already released), {@code not_stale} (still
     * inside the window, so there is nothing to release), {@code already_closed} (an operator closed
     * it before; no second row is written).
     *
     * <p>{@code not_stale} is also the answer when the conditional update matched nothing but the row
     * still reads {@code claimed}: the age condition — the same one the pre-check asks — is what said
     * no, so "not stale" is the reason. (Before, that path answered {@code not_claimable}, which named
     * a state that had not changed.)
     *
     * <p>The caller is checked twice, in this order: the action on the ledger, then the row. The row
     * gate is what keeps a tier-B non-administrator inside their own holds.
     *
     * @param actor the operator, recorded as the row's caller — a release is an act by a person
     * @return the closed claim, the tool it belongs to, and the row that recorded the act
     */
    @Transactional
    public Released release(Long claimId, Principal actor, String note, String evidenceRef) {
        String cleanedNote = note == null ? "" : note.trim();
        if (cleanedNote.length() < MIN_NOTE || cleanedNote.length() > MAX_NOTE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "note is required, at least " + MIN_NOTE + " and at most " + MAX_NOTE
                            + " characters");
        }
        String cleanedEvidence = evidenceRef == null ? "" : evidenceRef.trim();
        if (cleanedEvidence.length() > MAX_EVIDENCE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "evidenceRef is at most " + MAX_EVIDENCE + " characters");
        }

        WriteClaim claim = claims.findById(claimId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown write claim " + claimId));
        // The row half of the gate. `update` on the ledger says the caller may release holds at all;
        // this says which holds. A claim belongs to the user it was taken for and carries no other
        // scope fact, so an owner-scoped grant reaches its own and an administrator reaches all.
        //
        // 闸的行那一半。账本上的 `update` 说调用方**可以**释放占位；这一句说**哪些**占位。占位属于
        // **为谁而取**的那个人、不带别的范围事实，故按主人收窄的授予够得到本人的，管理员够得到全部。
        guard.requireAccess(actor, claim, GovernanceReadSubjects.TOOL_EFFECT, "update");
        if (!WriteClaim.CLAIMED.equals(claim.getStatus())) {
            throw refusal(WriteClaim.CLOSED_UNKNOWN.equals(claim.getStatus())
                    ? "already_closed" : "not_claimable", claim);
        }
        Instant now = Instant.now();
        if (!isStale(claim, now)) {
            throw refusal("not_stale", claim);
        }

        int closed = claims.closeUnknown(claimId, WriteClaim.CLAIMED, WriteClaim.CLOSED_UNKNOWN,
                now.minusMillis(ExecutionAxis.LEASE_MILLIS));
        if (closed == 0) {
            // A caller reclaimed the stale row, or another operator closed it, between the read and
            // the update. Report what actually happened rather than stamping over it.
            //
            // 读与更新之间，某个调用方把滞留行收了回去、或另一位运维关掉了它。如实报出实际发生的事，
            // 而不是盖掉它。
            WriteClaim after = claims.findById(claimId).orElse(null);
            if (after == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown write claim " + claimId);
            }
            // Still `claimed` means the update's age condition is what failed — either the row is at
            // the lease boundary or somebody reclaimed it and it is fresh again. Both are "not stale",
            // so that is the truthful kind; `not_claimable` named a state that never changed.
            //
            // 仍是 `claimed` 说明失败的是更新里的**年龄条件** —— 要么这一行正卡在租约边界，要么有人把它
            // 收回去了、它又变新鲜了。两者都是「不够陈旧」，故这才是如实的种类；`not_claimable` 点名的是
            // 一个从未改变的状态。
            throw refusal(WriteClaim.CLAIMED.equals(after.getStatus()) ? "not_stale"
                    : WriteClaim.CLOSED_UNKNOWN.equals(after.getStatus())
                            ? "already_closed" : "not_claimable", after);
        }

        AuditLog row = audit.append("claim_release", actor.userId(),
                detail(claim, cleanedNote, cleanedEvidence), false, null, claim.getToolName());
        return new Released(claimId, claim.getToolName(), WriteClaim.CLOSED_UNKNOWN,
                row == null ? null : row.getId());
    }

    /**
     * Strictly past the lease — the same boundary the conditional updates ask in SQL ({@code
     * claimedAt < staleBefore}). At exactly the lease the attempt is still inside it, so the answer
     * here and the answer from the update are the same one rather than differing by a millisecond.
     *
     * <p>严格越过租约 —— 与条件更新在 SQL 里问的边界相同（{@code claimedAt < staleBefore}）。恰好落在
     * 租约上时，那次尝试仍在租约之内，故这里的答案与更新的答案**是同一个**，而不是相差一毫秒。
     */
    private static boolean isStale(WriteClaim claim, Instant now) {
        return now.toEpochMilli() - claim.getClaimedAt().toEpochMilli() > ExecutionAxis.LEASE_MILLIS;
    }

    private static ResponseStatusException refusal(String kind, WriteClaim claim) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                kind + ": write claim " + claim.getId() + " is " + claim.getStatus());
    }

    /**
     * The row's text. It names the claim, the tool and the user the hold was taken for, so the row can
     * be read without joining the claim table — and says {@code closed_unknown}, because "released" is
     * this runtime's word for the <em>other</em> release (the one that proves nothing landed).
     *
     * 这一行的文字。它点出占位、工具与这份占位是为谁取的，于是不必连 `write_claims` 也能读懂 —— 并写明
     * `closed_unknown`，因为「released」在本运行时指的是**另一种**释放（证明什么都没落地的那一种）。
     */
    private static String detail(WriteClaim claim, String note, String evidence) {
        return "release write claim #" + claim.getId() + " (" + claim.getToolName() + ", as user "
                + claim.getUserId() + ") -> closed_unknown; note: " + note
                + (evidence.isEmpty() ? "" : "; evidence: " + evidence);
    }
}
