// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.conversation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    /** The oldest row, which is where the conversation's owner is recorded. */
    Optional<ConversationMessage> findFirstByConversationIdOrderByIdAsc(String conversationId);

    /**
     * The most recent turns, newest first.
     *
     * <p>Capped rather than complete: what a replier needs is the recent context, and a conversation
     * is unbounded.
     */
    List<ConversationMessage> findTop20ByConversationIdOrderByIdDesc(String conversationId);

    /**
     * The conversations a caller has — their ids, and nothing else.
     *
     * <p>This runtime keeps no conversation table, only its messages, so a conversation is whatever a
     * set of messages shares: the id is the whole record of its existence, and everything else about
     * it is derived. The same reason means there is nothing here to page — a caller's own
     * conversations are the bound.
     *
     * 某个调用方有哪些会话 —— 只有会话 id。本运行时**没有会话表**、只有它的消息，所以一个会话就是「一批消息
     * 共用的那个 id」：id 就是它存在的全部记录，其余一切都由消息推出。同一个原因也说明这里没有可分页的东西 ——
     * **调用方自己的会话**就是界限。
     */
    @Query("select distinct m.conversationId from ConversationMessage m where m.userId = :userId")
    List<String> conversationIdsFor(@Param("userId") String userId);

    /** The two most recent turns, newest first — the preview a list shows and the row's last activity. */
    List<ConversationMessage> findTop2ByConversationIdOrderByIdDesc(String conversationId);

    /** A conversation's turns, oldest first — uncapped, because a trace is of the whole of it. */
    List<ConversationMessage> findByConversationIdOrderByIdAsc(String conversationId);

    /**
     * How many turns a conversation has. The list carries only the newest two, so its message array
     * cannot answer this — the count is taken from the transcript itself.
     *
     * <p>一条会话有多少回合。列表只带最新的两条，故它的消息数组答不了这个问题 —— 条数从对话记录本身数。
     */
    long countByConversationId(String conversationId);
}
