// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.conversation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
