// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.conversation.ConversationMessage;
import cn.com.keelbase.runtime.conversation.ConversationStore;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.pipeline.DeterministicReplier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The conversational turn, in the shape the frontends read (ADR-0009).
 *
 * <p>Three things are load-bearing here and each fails a different way if it breaks: a caller gets a
 * conversation id that names something, a caller can tell a deterministic fallback from a model's
 * answer, and a conversation id is not a capability someone else can pick up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class ChatConversationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ConversationStore conversations;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void aTurnAnswersWithTheFieldsTheFrontendsRead() {
        Map<String, Object> body = chat("alice", "给客户建一条跟进记录", null);

        assertNotNull(body.get("conversationId"), "the frontends keep this and send it back");
        assertNotNull(body.get("reply"), "and render this");
        // The governance facts travel alongside, because this runtime has no SSE to carry them
        // (ADR-0009 D1).
        assertNotNull(body.get("status"), "the engine's answer is still here");
        assertTrue(body.containsKey("token"), "including the confirmation token");
    }

    @Test
    void theReplySaysWhoWroteIt() {
        Map<String, Object> body = chat("alice", "分析客户风险", null);

        assertEquals(DeterministicReplier.PROVIDER, body.get("provider"),
                "a caller must be able to tell a fallback from a model without guessing");
        assertEquals(DeterministicReplier.MODEL, body.get("model"));
    }

    @Test
    void aConversationIdTheRuntimeNeverIssuedIsNotAdopted() {
        Map<String, Object> body = chat("alice", "你好", "an-id-nobody-issued");

        assertNotEquals("an-id-nobody-issued", body.get("conversationId"),
                "the runtime issues the ids; an id it never issued is not a handle onto anything");
    }

    @Test
    void aConversationBelongsToItsOwner() {
        String mine = (String) chat("alice", "你好", null).get("conversationId");

        ResponseEntity<Map> asBob = chatRaw("bob", "你好", mine);

        assertEquals(403, asBob.getStatusCode().value(),
                "knowing the id is not the same as being allowed to continue the conversation");
    }

    @Test
    void bothTurnsAreRecorded() {
        String id = (String) chat("alice", "你好", null).get("conversationId");

        List<ConversationMessage> history = conversations.history(id);

        assertEquals(2, history.size(), "the caller's message and the reply");
        assertEquals(ConversationMessage.USER, history.get(0).getRole());
        assertEquals(ConversationMessage.ASSISTANT, history.get(1).getRole());
        assertEquals(new Principal("alice", "user").userId(), history.get(0).getUserId());
    }

    private Map<String, Object> chat(String user, String message, String conversationId) {
        ResponseEntity<Map> res = chatRaw(user, message, conversationId);
        assertEquals(200, res.getStatusCode().value(), "POST /ai/chat");
        return Envelopes.data(res.getBody());
    }

    private ResponseEntity<Map> chatRaw(String user, String message, String conversationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("message", message);
        body.put("customerId", 1);
        if (conversationId != null) {
            body.put("conversationId", conversationId);
        }
        return rest.postForEntity("/ai/chat", new HttpEntity<>(body, headers), Map.class);
    }
}
