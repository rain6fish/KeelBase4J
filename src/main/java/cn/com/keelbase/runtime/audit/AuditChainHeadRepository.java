// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, Long> {

    /**
     * Take the chain's write lock and hold it for the rest of the current transaction — which
     * includes the commit, and that is the whole point. Any other appender, in this process or
     * another one on the same database, blocks here until the holder's transaction ends.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select head from AuditChainHead head where head.id = :id")
    Optional<AuditChainHead> lockById(@Param("id") long id);
}
