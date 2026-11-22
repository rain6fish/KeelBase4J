// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationAuditLogRepository extends JpaRepository<OperationAuditLog, Long> {

    /** Newest first — the order an operations page reads. */
    Page<OperationAuditLog> findAllByOrderByIdDesc(Pageable pageable);

    /**
     * One caller's rows, newest first — the operations page narrowed to its owner, for an
     * owner-scoped grant on {@code OperationAudit}.
     *
     * 某个调用方自己的行，最新在前 —— 收窄到主人的操作审计页，供 `OperationAudit` 上带主人范围的授予。
     */
    Page<OperationAuditLog> findByUserIdOrderByIdDesc(String userId, Pageable pageable);
}
