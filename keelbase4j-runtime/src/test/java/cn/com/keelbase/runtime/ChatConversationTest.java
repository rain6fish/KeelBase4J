// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        // The reference reports the tools a turn used, and so does this (ADR-0009 D1).
        assertTrue(body.get("toolCalls") instanceof List<?> calls && calls.contains("create_followup"),
                "the tool this turn used is named, as the reference names it: " + body.get("toolCalls"));
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

        ResponseEntity<Map> asBob = chatRaw("bob", withCustomer("你好", mine));

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

    /**
     * The console does not send a customer id — it writes the one it is looking at into the first
     * message of the conversation, and no frontend sends the field. A runtime that only read the
     * field would answer this with a 500 from the tool, which is what the golden path found.
     */
    @Test
    void theCallerCanNameTheCustomerInTheMessage() {
        Map<String, Object> body = chat("alice", "当前客户「Acme」（ID 1）。给客户建一条跟进记录", null);

        assertEquals("pending_confirmation", body.get("status"),
                "the write the console asked for is proposed and waiting: " + body);
        assertNotNull(body.get("token"), "with a token for its confirmation card");
    }

    /**
     * The other half: a write with nobody to act on is not proposed at all. Handing a tool a null id
     * is how this failed before — the planner could route the words but had no customer to bind.
     */
    @Test
    void aWriteWithNoCustomerToActOnIsNotProposed() {
        ResponseEntity<Map> res = chatRaw("alice", message("给客户建一条跟进记录"));

        assertEquals(200, res.getStatusCode().value(), "not proposed, and not a failure either");
        Map<String, Object> body = Envelopes.data(res.getBody());
        assertNull(body.get("status"), "nothing was gated, because nothing was proposed");
        assertNotNull(body.get("reply"), "the caller is told so rather than left with an error");
    }

    /**
     * And the reference carries over. The console names the customer only in the <em>first</em> message
     * of a conversation, so on the second turn — "再建一条" — there is no id in the request and none in
     * that message. Reading the conversation is the only way left, and without it the second write of
     * every console conversation would go unrouted.
     */
    @Test
    void aLaterTurnStillKnowsWhichCustomerTheConversationIsAbout() {
        Map<String, Object> first = Envelopes.data(
                chatRaw("alice", message("当前客户「Acme」（ID 1）。给客户建一条跟进记录")).getBody());
        String conversationId = (String) first.get("conversationId");
        assertEquals("pending_confirmation", first.get("status"), "the first write is proposed");

        Map<String, Object> second = Envelopes.data(
                chatRaw("alice", reply(conversationId, "再建一条跟进记录")).getBody());

        assertEquals("pending_confirmation", second.get("status"),
                "the customer named in the first message still applies: " + second);
        assertNotNull(second.get("token"), "so the second write waits on a human too");
    }

    private static Map<String, Object> reply(String conversationId, String message) {
        Map<String, Object> body = message(message);
        body.put("conversationId", conversationId);
        return body;
    }

    private Map<String, Object> chat(String user, String message, String conversationId) {
        ResponseEntity<Map> res = chatRaw(user, withCustomer(message, conversationId));
        assertEquals(200, res.getStatusCode().value(), "POST /ai/chat");
        return Envelopes.data(res.getBody());
    }

    /** The body the golden path and the tests have always sent: an explicit customer id. */
    private static Map<String, Object> withCustomer(String message, String conversationId) {
        Map<String, Object> body = message(message);
        body.put("customerId", 1);
        if (conversationId != null) {
            body.put("conversationId", conversationId);
        }
        return body;
    }

    private static Map<String, Object> message(String message) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("message", message);
        return body;
    }

    private ResponseEntity<Map> chatRaw(String user, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        return rest.postForEntity("/ai/chat", new HttpEntity<>(body, headers), Map.class);
    }
}
