// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
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
 * The side-effect list, in the shape the runtime-neutral console reads it.
 *
 * <p>The console talks to two runtimes through one set of models. Its {@code ToolEffect} declares ten
 * required fields, and a runtime that answers with a bare array — or with items missing half of them
 * — is one the console cannot render, however correct the data is. This pins the contract on this
 * side of the wire so the two runtimes cannot drift apart without a test going red.
 *
 * <p>Asserting against a copied field list is deliberate: the point is that the fields the *console*
 * needs are present, so the list is written the way the console's model writes it, not the way this
 * runtime happens to produce it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class ToolEffectsContractTest {

    /**
     * The fields the console's {@code ToolEffect} declares as required. Optional ones (snapshots,
     * compensation group and the like) are absent on purpose: this runtime has no such concepts, and
     * inventing values for them would be worse than leaving the console's column empty.
     */
    private static final List<String> CONSOLE_REQUIRED_FIELDS = List.of(
            "id", "toolName", "conversationId", "resultType", "resultId",
            "argsHash", "createdAt", "targetExists", "targetSoftDeleted", "targetTitle");

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    CustomerRepository customers;

    @Autowired
    TestRestTemplate rest;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void theListAnswersTheConsolesEnvelopeAndFields() {
        // Produce a real effect through the trust loop, so the item under test is one the runtime
        // would actually have written.
        Principal alice = new Principal("alice", "user");
        Long customerId = customers.save(new Customer("Effects Co", "low", "alice")).getId();
        ExecutionOutcome pending = engine.execute("create_followup",
                Map.of("customerId", customerId, "note", "effects contract"), alice);
        engine.approve(pending.token(), alice);

        Map<String, Object> body = get("/ai/tool-effects?page=1&limit=20");

        assertTrue(body.containsKey("total"), "the console pages on total: " + body);
        assertEquals(1, body.get("page"), "and echoes the page it was asked for");
        assertEquals(20, body.get("limit"), "and the limit");
        List<?> items = (List<?>) body.get("items");
        assertNotNull(items, "items, not a bare array");
        assertTrue(!items.isEmpty(), "the effect just written should be listed");

        Map<?, ?> first = (Map<?, ?>) items.get(0);
        for (String field : CONSOLE_REQUIRED_FIELDS) {
            assertTrue(first.containsKey(field),
                    "the console's model requires '" + field + "'; got " + first.keySet());
        }
        assertEquals("follow_up", first.get("resultType"));
        assertTrue((Boolean) first.get("targetExists"), "the follow-up it created is there");
        assertEquals(false, first.get("targetSoftDeleted"), "and is not soft-deleted");
        assertEquals("local_compensate", first.get("revokeClass"));
        assertEquals(true, first.get("revocable"), "an executed local effect is revocable");
        assertEquals("effects contract", first.get("targetTitle"),
                "the follow-up's note is what the console shows as the target's title");
    }

    @Test
    void thePageSizeIsCapped() {
        Map<String, Object> body = get("/ai/tool-effects?page=1&limit=100000");

        assertEquals(100, body.get("limit"),
                "a caller cannot ask for the whole table by asking for a big limit");
    }

    private Map<String, Object> get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser("alice", delegationSecret));
        ResponseEntity<Map> res = rest.exchange(path, org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertEquals(200, res.getStatusCode().value(), path);
        return Envelopes.data(res.getBody());
    }
}
