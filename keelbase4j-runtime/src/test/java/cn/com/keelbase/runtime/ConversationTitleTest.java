// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * A conversation's title — the one fact about a conversation that no message can carry (M7 F-2).
 *
 * <p>The list is what a console reader scans to find their own action, and before this a row was an
 * id, a model and a time. The title is what the user names it; the count is the whole transcript's
 * size, because the list carries only the newest two turns and `messages.length` would say "2" for
 * every conversation past its first exchange.
 *
 * <p>会话的标题 —— 关于一条会话、任何消息都载不动的唯一事实（M7 F-2）。
 *
 * <p>控制台读者靠列表找自己那一次动作，而在它之前，一行只有 id、模型与时间。标题是**用户给它起的名字**；
 * 条数是**整段**对话记录的大小，因为列表只带最新的两条回合，`messages.length` 会对每一条超过一回合的
 * 会话都说「2」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class ConversationTitleTest {

    @Autowired
    TestRestTemplate rest;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void aNamedConversationIsListedUnderItsName() {
        String id = conversationOf("alice");

        assertEquals("第一条线索", rename("alice", id, "第一条线索").get("title"));

        Map<String, Object> listed = listedOf("alice", id);
        assertEquals("第一条线索", listed.get("title"), "the list is where the reader scans for it");
        assertEquals(2, ((Number) listed.get("messageCount")).intValue(),
                "the whole transcript's size, not the two turns the list carries");
    }

    @Test
    void anUnnamedConversationHasNoTitleAndStillCounts() {
        String id = conversationOf("alice");

        Map<String, Object> listed = listedOf("alice", id);

        assertNull(listed.get("title"), "no row, no name — not an empty string pretending to be one");
        assertEquals(2, ((Number) listed.get("messageCount")).intValue());
    }

    @Test
    void onlyTheOwnerCanNameIt() {
        String id = conversationOf("alice");

        ResponseEntity<Map> asBob = put("bob", id, Map.of("title", "not mine"));

        assertEquals(403, asBob.getStatusCode().value(), "knowing the id is not a capability");
        assertNull(listedOf("alice", id).get("title"), "and nothing was written");
    }

    @Test
    void aBlankTitleClearsTheName() {
        String id = conversationOf("alice");
        rename("alice", id, "先起个名");

        rename("alice", id, "   ");

        assertNull(listedOf("alice", id).get("title"), "clearing is a delete, so absence has one representation");
    }

    @Test
    void anOverlongTitleIsRefusedRatherThanTruncated() {
        String id = conversationOf("alice");

        ResponseEntity<Map> tooLong = put("alice", id, Map.of("title", "x".repeat(201)));

        assertEquals(400, tooLong.getStatusCode().value(),
                "a silently shortened title would look like the one the user typed");
        assertNull(listedOf("alice", id).get("title"), "and the refusal wrote nothing");
    }

    @Test
    void namingAnUnknownConversationIs404() {
        ResponseEntity<Map> res = put("alice", "a-conversation-nobody-opened", Map.of("title", "x"));

        assertEquals(404, res.getStatusCode().value());
    }

    private Map<String, Object> rename(String user, String id, String title) {
        ResponseEntity<Map> res = put(user, id, Map.of("title", title));
        assertEquals(200, res.getStatusCode().value(), "PUT /ai/conversations/{id}/title");
        return Envelopes.data(res.getBody());
    }

    private ResponseEntity<Map> put(String user, String id, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        return rest.exchange("/ai/conversations/" + id + "/title", HttpMethod.PUT,
                new HttpEntity<>(body, headers), Map.class);
    }

    private String conversationOf(String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "你好");
        body.put("customerId", 1);
        ResponseEntity<Map> res = rest.postForEntity("/ai/chat", new HttpEntity<>(body, headers), Map.class);
        assertEquals(200, res.getStatusCode().value(), "POST /ai/chat");
        return (String) Envelopes.<Map<String, Object>>data(res.getBody()).get("conversationId");
    }

    private Map<String, Object> listedOf(String user, String id) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        ResponseEntity<Map> res = rest.exchange("/ai/conversations", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertEquals(200, res.getStatusCode().value(), "GET /ai/conversations");
        List<Map<String, Object>> items = Envelopes.data(res.getBody());
        return items.stream()
                .filter((item) -> id.equals(item.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the conversation is not in the caller's list: " + id));
    }
}
