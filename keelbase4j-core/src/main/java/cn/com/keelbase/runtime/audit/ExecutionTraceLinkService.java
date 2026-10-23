// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the one link row per governed attempt.
 *
 * <p>It runs in its own transaction on purpose: the link is auxiliary to the audit chain, so a
 * failure to write it must not roll the audit row back. A repeated attempt finds the row already
 * there and does nothing.
 *
 * 每次受治理尝试写一行关联。
 *
 * <p>刻意跑在自己的事务里：关联是审计链的**旁路**，写不成不应把审计行回滚。同一次尝试再来一次时读到已有行，
 * 不再写。
 */
@Service
public class ExecutionTraceLinkService {

    private final ExecutionTraceLinkRepository repository;

    public ExecutionTraceLinkService(ExecutionTraceLinkRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIfAbsent(String executionId, String traceId, String tool, String userId,
                               String conversationId) {
        if (executionId == null || executionId.isBlank() || repository.existsById(executionId)) {
            return;
        }
        repository.save(new ExecutionTraceLink(executionId, normalize(traceId), tool, userId,
                conversationId));
    }

    private static String normalize(String traceId) {
        return traceId == null || traceId.isBlank() ? null : traceId;
    }
}
