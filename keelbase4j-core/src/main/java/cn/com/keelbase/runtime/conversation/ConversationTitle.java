// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A conversation's name, as its owner gave it — the work ledger's one fact about a conversation that
 * no message can carry.
 *
 * <p>Conversations in this runtime are derived from their messages: the id is the whole record of
 * their existence. A name is therefore not something a message could hold, and it is not a digest of
 * the messages either — deriving one from the text would be the content summary the conversation-meta
 * view deliberately does not carry (AU-4). It is what the user typed, stored beside the transcript and
 * read back on the list and the trace. A blank name is the absence of a row: clearing is a delete.
 *
 * <p>一条会话的名字，由它的属主给出 —— 工作账本里「关于会话、而任何一条消息都载不动」的唯一事实。
 *
 * <p>本运行时的会话由消息推出来：id 就是它存在的全部记录。故名字不是消息能装下的东西，也不是消息的摘要 ——
 * 从正文派生一个，正是 `conversation-meta` 视图刻意不带的**内容摘要**（AU-4）。它是**用户写下**的那个名字，
 * 存在对话记录旁边，在列表与追踪上读回。空名字就是没有这一行：清除等于删除。
 */
@Entity
@Table(name = "conversation_titles")
public class ConversationTitle {

    /** The same ceiling the console's input enforces; the column and the API agree on one number. */
    /** 与控制台输入框同一条上限；列与接口共用一个数字。 */
    public static final int MAX_LENGTH = 200;

    @Id
    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false, length = MAX_LENGTH)
    private String title;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ConversationTitle() {
    }

    public ConversationTitle(String conversationId, String userId, String title) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.title = title;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
