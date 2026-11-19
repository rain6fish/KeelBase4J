// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.governance.ExecutionAxis;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
 * <p>A deployment whose audit granularity is {@code off} writes no row, by policy; the release still
 * happens and the answer's {@code auditRowId} is null. That absence is the policy, not a failure.
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

    public WriteClaimReleaseService(WriteClaimRepository claims, AuditService audit) {
        this.claims = claims;
        this.audit = audit;
    }

    /**
     * Close {@code claimId} as unknown and put one row on the chain.
     *
     * <p>Refusals are kinds, not a bare {@code false}: {@code not_found} (no such row),
     * {@code not_claimable} (the execution settled or was already released), {@code not_stale} (still
     * inside the window, so there is nothing to release), {@code already_closed} (an operator closed
     * it before; no second row is written).
     *
     * @param actorUserId the operator, recorded as the row's caller — a release is an act by a person
     * @return the closed claim, the tool it belongs to, and the row that recorded the act
     */
    public Released release(Long claimId, String actorUserId, String note, String evidenceRef) {
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
            throw refusal(WriteClaim.CLOSED_UNKNOWN.equals(after.getStatus())
                    ? "already_closed" : "not_claimable", after);
        }

        AuditLog row = audit.append("claim_release", actorUserId,
                detail(claim, cleanedNote, cleanedEvidence), false, null, claim.getToolName());
        return new Released(claimId, claim.getToolName(), WriteClaim.CLOSED_UNKNOWN,
                row == null ? null : row.getId());
    }

    private static boolean isStale(WriteClaim claim, Instant now) {
        return now.toEpochMilli() - claim.getClaimedAt().toEpochMilli() >= ExecutionAxis.LEASE_MILLIS;
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
