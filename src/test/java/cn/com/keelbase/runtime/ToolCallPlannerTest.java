// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.com.keelbase.runtime.pipeline.IntentPlan;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * JV-11 — the AI pipeline seam.
 *
 * <p>Two things have to hold. The planner can be replaced by a bean, which is what makes it a seam
 * rather than a hardcoded router. And whatever it proposes is still gated by the runtime, which is
 * what makes it a seam rather than a way around the boundary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ToolCallPlannerTest {

    /**
     * Stands in for a model-driven pipeline: it proposes the write tool whatever was asked, and knows
     * nothing about risk levels — because risk is not a planner's to know.
     */
    @TestConfiguration
    static class AlwaysProposesAWrite {

        @Bean
        @Primary
        ToolCallPlanner planner() {
            return (message, context) -> Optional.of(new IntentPlan(
                    "create_followup",
                    Map.of("customerId", context.get("customerId"), "note", "proposed by the planner")));
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void aReplacedPlannerStillHasItsProposalGated() {
        Map<?, ?> outcome = chat("bob", "这些话与规划器无关——它总会提议写工具");

        assertEquals("pending_confirmation", outcome.get("status"),
                "a planner proposes a call; it does not decide that a write may happen");
        assertNotNull(outcome.get("token"), "and the write must still ask a human");
    }

    private Map<?, ?> chat(String userId, String message) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(userId, delegationSecret));
        ResponseEntity<Map> res = rest.postForEntity("/ai/chat",
                new HttpEntity<>(Map.of("message", message, "customerId", 1), headers), Map.class);
        assertEquals(200, res.getStatusCode().value(), "POST /ai/chat");
        return res.getBody();
    }
}
