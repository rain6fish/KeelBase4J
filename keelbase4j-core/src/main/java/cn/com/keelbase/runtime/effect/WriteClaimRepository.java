// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The claim rows, and the three transitions that move one. Each transition is conditional on the state
 * it moves out of, so of any number of callers at most one lands — the same arbitration principle the
 * confirmation queue uses, for the same reason: read-then-write would leave a window in which two
 * callers both see what they wanted to see.
 *
 * <p><b>The transaction is declared here, not on the service, and that is deliberate.</b>
 * {@code WriteClaimService.claim} must not run inside a transaction: its insert has to be able to
 * fail on the unique key and roll back on its own, or the read-back that follows it reads from a
 * poisoned persistence context. A {@code @Modifying} update does need one, so each of these three
 * carries its own — short, independent, and committed before the caller looks at the answer.
 */
public interface WriteClaimRepository extends JpaRepository<WriteClaim, Long> {

    Optional<WriteClaim> findByIdempotencyKey(String idempotencyKey);

    /**
     * Re-claim a row a previous attempt released. Conditional on {@code released}, so a caller racing
     * another re-claim loses rather than both proceeding, and a row that is {@code claimed} or
     * {@code settled} is not reachable this way at all.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update WriteClaim c set c.status = :to, c.claimedAt = :at, c.settledAt = null "
            + "where c.idempotencyKey = :key and c.status = :from")
    int reclaim(@Param("key") String key, @Param("from") String from, @Param("to") String to,
                @Param("at") Instant at);

    /** The execution finished and its effect is recorded — or there was no effect to record. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update WriteClaim c set c.status = :to, c.effectId = :effectId, c.settledAt = :at "
            + "where c.idempotencyKey = :key and c.status = :from")
    int settle(@Param("key") String key, @Param("from") String from, @Param("to") String to,
               @Param("effectId") Long effectId, @Param("at") Instant at);

    /** The attempt failed without landing, so a retry may take the key again. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update WriteClaim c set c.status = :to where c.idempotencyKey = :key and c.status = :from")
    int release(@Param("key") String key, @Param("from") String from, @Param("to") String to);
}
