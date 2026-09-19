// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConfirmationRequestRepository extends JpaRepository<ConfirmationRequest, Long> {

    Optional<ConfirmationRequest> findByToken(String token);

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
     */
    @Modifying(clearAutomatically = true)
    @Query("update ConfirmationRequest r set r.status = :to, r.decidedAt = :at "
            + "where r.token = :token and r.operatorId = :operatorId and r.status = :from")
    int claim(@Param("token") String token, @Param("operatorId") String operatorId,
              @Param("from") String from, @Param("to") String to, @Param("at") Instant at);
}
