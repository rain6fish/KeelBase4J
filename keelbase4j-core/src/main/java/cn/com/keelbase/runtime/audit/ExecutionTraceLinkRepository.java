// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The execution-to-trace links. Reading by trace id answers "which governed attempts happened inside
 * this host request", which is the join the host's own operation log needs.
 *
 * 执行与 trace 的关联。按 trace id 读取回答「这一次宿主请求里发生了哪些受治理尝试」—— 正是宿主自己的
 * 操作日志需要的 join。
 */
public interface ExecutionTraceLinkRepository extends JpaRepository<ExecutionTraceLink, String> {

    List<ExecutionTraceLink> findByTraceIdOrderByCreatedAtAsc(String traceId);
}
