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
     */
    @Modifying(clearAutomatically = true)
    @Query("update ConfirmationRequest r set r.executionClaimedAt = :at "
            + "where r.token = :token and r.status = :approved and r.executedAt is null "
            + "and (r.executionClaimedAt is null or r.executionClaimedAt < :staleBefore)")
    int claimExecution(@Param("token") String token, @Param("approved") String approved,
                       @Param("at") Instant at, @Param("staleBefore") Instant staleBefore);
}
