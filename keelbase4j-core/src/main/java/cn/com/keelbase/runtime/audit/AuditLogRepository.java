// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * The audit rows. Reading is by specification rather than by one fixed query: a request filters the
 * chain by whichever of caller, period and outcome it actually names, and this way the repository
 * never has to ask the dialect-dependent question of how a typed parameter compares against null.
 *
 * 审计行的仓库。读走规格（specification）、不走一条固定的查询：请求按它**真的点名**的那些条件筛选链 ——
 * 调用者、一段时间、结果 —— 这样仓库就不必去问那个依方言而定的问题：一个有类型的参数怎么和 null 作比较。
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>,
        JpaSpecificationExecutor<AuditLog> {

    Optional<AuditLog> findTopByOrderByIdDesc();

    List<AuditLog> findAllByOrderByIdAsc();
}
