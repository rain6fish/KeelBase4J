// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConfirmationRequestRepository extends JpaRepository<ConfirmationRequest, Long> {

    Optional<ConfirmationRequest> findByToken(String token);

    /**
     * This operator's own confirmation records, newest first — the Action Center's discovery face
     * (ADR-0016). Narrowed to the operator here rather than in a caller: whose rows these are is part
     * of the question, not a filter applied afterwards.
     *
     * <p>Only {@code immediate} rows: an approval row is waiting on <em>somebody else</em>, and this
     * list is "waiting on me" — listing a row its viewer may not answer would offer an action bound to
     * fail. The reference keeps its own out-of-band path to R3 for the same reason.
     *
     * <p>只收 {@code immediate} 行：审批行等的是**别人**，而这份列表问的是「**在等我什么**」——把一行
     * 它的读者无权回答的东西列出来，等于给出一个必然失败的操作。参照把自己的对话外路径也限制在 R3，同一个道理。
     */
    List<ConfirmationRequest> findByOperatorIdAndModeOrderByCreatedAtDesc(String operatorId, String mode);

    /**
     * The offline window's arbitration: every still-{@code pending} row whose window has closed
     * becomes {@code timeout}.
     *
     * <p>Deliberately a conditional bulk update rather than a read-then-write. {@code status} is part
     * of the {@code where} clause, so a row decided while this runs is simply not selected — the
     * decision wins and the sweeper cannot overwrite one. The reference puts it the same way: the
     * conditional update is the only place either window is arbitrated, which is what keeps a
     * concurrent or repeated decision from executing a tool twice.
     */
    @Modifying(clearAutomatically = true)
    @Query("update ConfirmationRequest r set r.status = :to, r.decidedAt = :at "
            + "where r.status = :from and r.createdAt < :cutoff")
    int expireStale(@Param("from") String from, @Param("to") String to,
                    @Param("cutoff") Instant cutoff, @Param("at") Instant at);

    /**
     * Claim a still-{@code pending} row for this operator, moving it to a terminal status — and
     * report whether the claim landed ({@code 1}) or somebody else got there first ({@code 0}).
     *
     * <p>This is the arbitration point for a decision, on the same principle as {@link #expireStale}:
     * the transition is conditional on the state it is moving out of, so of any number of concurrent
     * deciders exactly one can win. Reading the row and then updating it by id would leave a window
     * between the two in which two callers both see {@code pending} and both run the tool — the tool
     * running twice is precisely what the frozen invariant forbids.
     *
     * <p>{@code operatorId} is part of the condition so the claim stands on its own: the row this
     * moves is the one belonging to the operator who is deciding it.
     *
     * <p>{@code mode} is in the condition as well, and it is what keeps the two-person rule from being
     * walked around: an approval row also carries the operator's id, so without it the operator could
     * answer their own high-impact request through this path. Matching on the mode rather than on the
     * risk level is deliberate — the two are not synonyms (see {@link ConfirmationMode}), and the mode
     * is what decides who may answer a row.
     *
     * <p>{@code mode} 也在条件里，它正是**不让双人规则被绕过**的那一条：审批行同样带着操作者的 id，少了
     * 它，操作者就能经这条路回答自己的高影响请求。按 mode 而不是按风险级匹配是有意的——两者不是同义词
     * （见 {@link ConfirmationMode}），而 mode 决定的才是谁可以回答一行。
     */
    @Modifying(clearAutomatically = true)
    @Query("update ConfirmationRequest r set r.status = :to, r.decidedAt = :at "
            + "where r.token = :token and r.operatorId = :operatorId and r.status = :from "
            + "and r.mode = :mode")
    int claim(@Param("token") String token, @Param("operatorId") String operatorId,
              @Param("from") String from, @Param("to") String to, @Param("at") Instant at,
              @Param("mode") String mode);

    /**
     * Claim a still-{@code pending} row for this operator <em>outside the conversation</em> — the
     * offline window is part of the condition, so a row whose window has closed cannot be moved here
     * at all.
     *
     * <p>The window belongs in the condition for the same reason the status does: read the row, check
     * the clock, then update, and a window closing in between still lets the decision through. The
     * frozen lifecycle says an expired decision is rejected with no transition, and a condition is the
     * only way to say that and mean it.
     */
    @Modifying(clearAutomatically = true)
    @Query("update ConfirmationRequest r set r.status = :to, r.decidedAt = :at "
            + "where r.token = :token and r.operatorId = :operatorId and r.status = :from "
            + "and r.createdAt >= :cutoff and r.mode = :mode")
    int decideOutOfBand(@Param("token") String token, @Param("operatorId") String operatorId,
                        @Param("from") String from, @Param("to") String to, @Param("at") Instant at,
                        @Param("cutoff") Instant cutoff, @Param("mode") String mode);

    /**
     * Claim a still-{@code pending} <em>approval</em> row for whoever is answering it, recording who
     * that was — and report whether the claim landed.
     *
     * <p>Same arbitration as {@link #claim}, and the same reason for being a conditional update: of
     * any number of approvers deciding at once, exactly one gets a hit and only that one runs the
     * tool. What differs is the condition — not the operator's id (the answer comes from somebody
     * else by construction) but the mode, so this path can only ever move an approval row and the
     * operator's own path can only ever move an immediate one.
     *
     * <p>{@code approverId} is written by the same statement that moves the row, so "somebody approved
     * it" and "who" cannot disagree.
     *
     * <p>与 {@link #claim} 同一套仲裁，同一套「为什么必须是条件更新」的理由：任意多个审批人同时裁决，只有
     * 一方命中，也只有那一方会跑工具。不同的是条件——不是操作者的 id（回答按构造就来自别人），而是 **mode**，
     * 于是这条路只能移动审批行，而操作者本人那条路只能移动即时行。
     *
     * <p>{@code approverId} 由**同一条**移动该行的语句写入，所以「有人批了它」与「是谁批的」不可能各说各话。
     */
    @Modifying(clearAutomatically = true)
    @Query("update ConfirmationRequest r set r.status = :to, r.approverId = :approverId, r.decidedAt = :at "
            + "where r.token = :token and r.status = :from and r.mode = :mode")
    int claimApproval(@Param("token") String token, @Param("approverId") String approverId,
                      @Param("from") String from, @Param("to") String to, @Param("at") Instant at,
                      @Param("mode") String mode);

    /**
     * Claim one <em>execution</em> attempt — the lease ADR-0016 described but left as a read-then-write.
     *
     * <p>It has to be a conditional update for a reason that only shows up once retrying exists: a
     * retry is a second entry into the same tail, and a read-then-write lets it and a live attempt both
     * pass the check and run the tool twice. The condition is the whole rule — the row is approved, it
     * has not succeeded, and its claim is either absent or older than the lease, which is exactly when
     * a previous attempt can be presumed dead.
     *
     * <p>{@code executedAt is null} in the condition is what makes a succeeded row unclaimable, so no
     * path can re-run a write that already landed.
     *
     * <p>**认领一次执行尝试** —— ADR-0016 描述过、却留成了读改写的那个租约。
     *
     * <p>它必须是条件更新，理由只有在**重试存在之后**才显形：重试是同一段尾巴的第二个入口，而读改写会让它
     * 与在途的那次**都通过检查**、把工具跑两遍。条件本身就是全部规则——行已批准、尚未成功、且它的认领要么
     * 不存在、要么早于租约，而那正是「上一次尝试可以推定已死」的时刻。
     *
     * <p>条件里的 {@code executedAt is null} 使**已成功的行不可再被认领**，于是没有任何路径能重跑一次已经
     * 落地的写。
     */
    @Modifying(clearAutomatically = true)
    @Query("update ConfirmationRequest r set r.executionClaimedAt = :at "
            + "where r.token = :token and r.status = :approved and r.executedAt is null "
            + "and (r.executionClaimedAt is null or r.executionClaimedAt < :staleBefore)")
    int claimExecution(@Param("token") String token, @Param("approved") String approved,
                       @Param("at") Instant at, @Param("staleBefore") Instant staleBefore);
}
