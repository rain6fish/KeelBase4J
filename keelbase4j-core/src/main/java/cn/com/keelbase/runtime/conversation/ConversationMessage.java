// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One turn of a conversation.
 *
 * <p><b>This is a transcript, not memory.</b> It exists so that a conversation id names something
 * that really happened — the alternative was to return an id that pointed at nothing, which is the
 * kind of small invention this codebase does not make. There is deliberately nothing here but the
 * turns themselves: no embeddings, no retrieval, no summarisation, no memory strategy. Those are the
 * parked "self-hosted Memory/RAG/Pipeline" (ADR-0004), and ADR-0009 D3 admits the transcript
 * precisely because it is not that.
 */
@Entity
@Table(name = "conversation_messages")
public class ConversationMessage {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    /** Whose conversation this is — the id alone is not a capability. */
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false, length = 4000)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ConversationMessage() {
    }

    public ConversationMessage(String conversationId, String userId, String role, String content) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.role = role;
        this.content = content;
    }

    public Long getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
