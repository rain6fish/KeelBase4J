// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationAuditLogRepository extends JpaRepository<OperationAuditLog, Long> {

    /** Newest first — the order an operations page reads. */
    Page<OperationAuditLog> findAllByOrderByIdDesc(Pageable pageable);
}
