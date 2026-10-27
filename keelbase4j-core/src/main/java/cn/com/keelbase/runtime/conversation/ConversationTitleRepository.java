// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.conversation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationTitleRepository extends JpaRepository<ConversationTitle, String> {

    /**
     * The titles of the given conversations, in one query — a list of twenty rows must not become
     * twenty lookups.
     *
     * 给定这些会话的名字，一次查询取回 —— 一页二十行不该变成二十次查询。
     */
    List<ConversationTitle> findByConversationIdIn(Collection<String> conversationIds);
}
