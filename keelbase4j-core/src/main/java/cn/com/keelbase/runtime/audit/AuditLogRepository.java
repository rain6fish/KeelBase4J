// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Optional<AuditLog> findTopByOrderByIdDesc();

    List<AuditLog> findAllByOrderByIdAsc();
}
