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

    /**
     * The rows a conversation proposed, oldest first — the spine of its decision trace. Ordering by
     * id is ordering by time: ids are issued by this deployment, in the order the rows were written.
     *
     * 某条会话提议的那些行，最早的在前 —— 那就是它决策轨迹的脊。按 id 排序即按时间排序：id 由本部署按写下的
     * 先后发出。
     */
    List<AuditLog> findByConversationIdOrderByIdAsc(String conversationId);

    /**
     * The rows of one execution, oldest first — the intent row and the result row of a single
     * attempt, paired by a key that no other execution shares.
     *
     * 一次执行的那些行，最早的在前 —— 单次尝试的 intent 行与结果行，用一条没有别的执行会共享的键配对。
     */
    List<AuditLog> findByExecutionIdOrderByIdAsc(String executionId);
}
