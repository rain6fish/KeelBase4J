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
}
