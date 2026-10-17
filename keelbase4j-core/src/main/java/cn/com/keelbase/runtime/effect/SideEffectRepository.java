// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SideEffectRepository extends JpaRepository<SideEffect, Long> {

    Optional<SideEffect> findByIdempotencyKey(String idempotencyKey);

    /**
     * The effect one execution wrote — the third row of the same attempt. Empty for an execution that
     * produced no effect (a read) or reused one (the row belongs to the execution that wrote it).
     *
     * 一次执行写下的 effect —— 同一次尝试的第三行。没有产出 effect 的执行（读）或复用了一个执行（那一行属于
     * 写下它的那次执行）为空。
     */
    List<SideEffect> findByExecutionIdOrderByIdAsc(String executionId);

    /** The effects a conversation's calls produced, oldest first — a trace's effect steps. */
    List<SideEffect> findByConversationIdOrderByIdAsc(String conversationId);

    List<SideEffect> findByUserIdOrderByIdDesc(String userId);

    /** The same view, paged at the database — which is what the console asks for. */
    Page<SideEffect> findByUserIdOrderByIdDesc(String userId, Pageable pageable);

    /**
     * That view narrowed to one conversation, paged — a reader that wants *this turn's* writes.
     *
     * <p>The pair above and this one are the two halves of the same question, and both stay scoped to
     * the caller: naming somebody else's conversation answers with nothing rather than with their rows.
     *
     * 同一个视图收窄到**一条会话**、分页 —— 一个想要**这一轮的**写入的读者要的就是它。
     *
     * <p>上面那一对与这一对是同一个问题的两半，且**都**按调用方收束：点名**别人**的会话，答案是**空**、不是他们的行。
     */
    Page<SideEffect> findByUserIdAndConversationIdOrderByIdDesc(String userId, String conversationId,
                                                               Pageable pageable);

    /**
     * Every caller's effects for one conversation, paged — the manager's form of the pair above.
     *
     * 所有人的、属于同一条会话的副作用，分页 —— 上面那一对的**管理员**形态。
     */
    Page<SideEffect> findByConversationIdOrderByIdDesc(String conversationId, Pageable pageable);
}
