// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.KeelBase4JApplication;
import cn.com.keelbase.runtime.pipeline.RuleBasedPlanner;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The adapter, on the real seam, inside a running application.
 *
 * <p>Three things have to hold at once, and each is a different failure if it does not:
 *
 * <ol>
 *   <li><b>The module takes the seam.</b> A request the runtime's rule-based planner could not route
 *       at all ("盘点一下") is routed — because a model is configured, the model-driven planner is
 *       the one answering. This is the first thing outside the runtime to rely on JV-14's
 *       arrangement, where the default yields instead of having to be over-ranked.
 *   <li><b>Nothing was executed.</b> The model proposed a write; the write is still waiting for a
 *       human. A planner that could execute would have made this test return {@code executed}.
 *   <li><b>The model was not told how the call is governed.</b> The catalogue it received names the
 *       tools and says nothing about their risk level or confirmation requirement.
 * </ol>
 */
@SpringBootTest(classes = KeelBase4JApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@Import(SpringAiPlannerOnTheSeamTest.StubModel.class)
class SpringAiPlannerOnTheSeamTest {

    /** A model that always proposes the write tool, and remembers what it was asked. */
    @TestConfiguration
    static class StubModel {

        static final List<String> PROMPTS = new ArrayList<>();

        @Bean
        ChatModel chatModel() {
            return prompt -> {
                PROMPTS.add(prompt.getContents());
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "{\"tool\": \"create_followup\", \"args\": {\"customerId\": 1}}"))));
            };
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApplicationContext context;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void theAdapterTakesTheSeamAndTheDefaultStandsDown() {
        assertTrue(context.getBean(ToolCallPlanner.class) instanceof SpringAiToolCallPlanner,
                "with a model configured, the model-driven planner is the one on the seam");
        assertTrue(context.getBeansOfType(RuleBasedPlanner.class).isEmpty(),
                "and the runtime's default is not registered alongside it");
    }

    @Test
    void aRequestTheRulesCannotRouteIsRoutedByTheModel() {
        ResponseEntity<Map> res = chat("alice", "盘点一下这个客户");

        assertEquals(200, res.getStatusCode().value(),
                "the rule-based planner finds no keyword here; the model does");
        assertEquals("pending_confirmation", data(res).get("status"),
                "a proposal is not a permission: the write still waits for a human");
        assertNotNull(data(res).get("token"), "and it still returns a confirmation token");
    }

    @Test
    void theModelIsNotToldHowTheCallIsGoverned() {
        chat("alice", "盘点一下这个客户");

        assertFalse(StubModel.PROMPTS.isEmpty(), "the whole point is that the model was consulted");
        String prompt = StubModel.PROMPTS.get(StubModel.PROMPTS.size() - 1);
        assertTrue(prompt.contains("create_followup"), "it is told which tools exist");
        assertFalse(prompt.contains("R3"),
                "it is not told their risk level: " + prompt);
    }

    private ResponseEntity<Map> chat(String userId, String message) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(userId, delegationSecret));
        return rest.postForEntity("/ai/chat",
                new HttpEntity<>(Map.of("message", message, "customerId", 1), headers), Map.class);
    }

    /** Responses arrive in the api-response envelope; the outcome is under {@code data}. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> res) {
        return (Map<String, Object>) res.getBody().get("data");
    }
}
