// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.governance.ConfirmationRequest;
import cn.com.keelbase.runtime.governance.ConfirmationRequestRepository;
import cn.com.keelbase.runtime.governance.ConfirmationStore;
import cn.com.keelbase.runtime.identity.Principal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The operator's own confirmation list — the Action Center's discovery face (ADR-0016), in the frozen
 * {@code my-confirmation-item} shape.
 *
 * <p>Two things are being asserted, and only one of them is a list: that a caller sees their own rows
 * and nobody else's (the scope is part of the question, not a filter a caller could lift), and that the
 * item carries what the contract requires — including the execution axis, which is the half of the v2
 * upgrade that makes "approved but the write never ran" visible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "keelbase.chat.stream-wait-ms=800")
@ActiveProfiles("test")
class MyConfirmationsListTest {

    private static final String WRITE_THAT_WAITS = "给客户建一条跟进记录";

    /** A window that has already closed: by the time it opens, every pending row is past it. */
    private static final long ALREADY_CLOSED = 0L;

    @LocalServerPort
    int port;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Autowired
    CustomerRepository customers;

    @Autowired
    ConfirmationRequestRepository confirmations;

    @Autowired
    ConfirmationStore store;

    private Long customerId;

    @BeforeEach
    void oneCustomerAndNoLeftoverConfirmations() {
        confirmations.deleteAll();
        customerId = customers.save(new Customer("Action Center Co", "low", "alice")).getId();
    }

    @Test
    void theCallerSeesTheirOwnRowsAndNobodyElses() throws Exception {
        String aliceToken = pendingWriteFor("alice");
        pendingWriteFor("bob");

        List<Map<String, Object>> mine = list("alice");

        assertEquals(1, mine.size(), "only her own row: " + mine);
        assertEquals(aliceToken, mine.get(0).get("token"));
    }

    @Test
    void statusNarrowsTheList() throws Exception {
        String token = pendingWriteFor("alice");
        decide("alice", token, "approve");

        List<Map<String, Object>> pending = list("alice", "pending");
        List<Map<String, Object>> approved = list("alice", "approved");

        assertTrue(pending.isEmpty(), "the approved row is no longer waiting: " + pending);
        assertEquals(1, approved.size(), "and it is what the approved view holds: " + approved);
        assertEquals(token, approved.get(0).get("token"));
    }

    @Test
    void theItemAnswersTheFrozenShape() throws Exception {
        String token = pendingWriteFor("alice");

        Map<String, Object> item = list("alice").get(0);

        assertEquals(token, item.get("token"));
        assertEquals("create_followup", item.get("toolName"));
        assertEquals("immediate", item.get("mode"),
                "this runtime produces one mode, and saying so is the honest answer");
        assertEquals("R3", item.get("riskLevel"));
        assertEquals(ConfirmationLifecycle.PENDING, item.get("status"));
        assertEquals("local_compensate", item.get("revokeClass"),
                "the tool's own revoke class, not a guess");
        assertNotNull(item.get("arguments"), "the arguments the write would run with");
        assertNotNull(item.get("createdAt"));
        // Present and null: the contract has these fields, and a console that reads the reference reads
        // them. A summary would need a presentation layer this runtime does not have, and an impact
        // preview and a run batch belong to concepts it does not have either.
        for (String absent : List.of("summary", "impact", "run")) {
            assertTrue(item.containsKey(absent), absent + " must be present, not omitted");
            assertNull(item.get(absent), absent + " has no counterpart here, so it is null");
        }
        assertNotNull(item.get("expiresAt"), "a pending row still has an offline window");
        assertNull(item.get("executionState"),
                "a row nobody decided has no execution dimension — not_started would imply it will run");
        assertTrue(item.containsKey("executionState") && item.containsKey("executedAt")
                        && item.containsKey("executionError"),
                "the v2 execution axis is on the item: " + item.keySet());
    }

    @Test
    void anApprovedWriteReportsItsExecution() throws Exception {
        String token = pendingWriteFor("alice");

        decide("alice", token, "approve");

        Map<String, Object> item = list("alice", "approved").get(0);
        assertEquals("succeeded", item.get("executionState"), "the write ran: " + item);
        assertNotNull(item.get("executedAt"), "and it says when");
        assertNull(item.get("executionError"), "with nothing to report as an error");
    }

    @Test
    void aDeclinedRowHasNoExecutionDimension() throws Exception {
        String token = pendingWriteFor("alice");

        decide("alice", token, "decline");

        Map<String, Object> item = list("alice", "declined").get(0);
        assertNull(item.get("executionState"), "nothing was going to run, so there is nothing to report");
        assertNull(item.get("executedAt"));
        assertFalse(item.containsKey("expiresAt"),
                "the contract omits expiresAt on terminal rows, and it is not nullable");
    }

    @Test
    void aPendingRowWhoseWindowClosedIsLeftOut() {
        ConfirmationRequest fresh = store.create(new Principal("alice", "user"), "create_followup", "{}", "R3");

        assertEquals(1, store.mine("alice", null, Instant.now(),
                ConfirmationLifecycle.DEFAULT_OFFLINE_TTL_MILLIS).size(), "inside its window, it is listed");
        assertTrue(store.mine("alice", null, Instant.now(), ALREADY_CLOSED).isEmpty(),
                "past its window it is not: offering a button that is bound to fail is what the window is for");
        assertEquals(ConfirmationLifecycle.PENDING, statusOf(fresh.getToken()),
                "and it is still pending — the sweeper is what ends it, not the list");
    }

    @Test
    void theListIsCappedAndNewestFirst() {
        for (int i = 0; i < 51; i++) {
            store.create(new Principal("alice", "user"), "create_followup", "{\"i\":" + i + "}", "R3");
        }

        List<ConfirmationRequest> mine = store.mine("alice", null, Instant.now(),
                ConfirmationLifecycle.DEFAULT_OFFLINE_TTL_MILLIS);

        assertEquals(50, mine.size(), "the console shows what is recent, not the whole table");
        assertTrue(mine.get(0).getCreatedAt().compareTo(mine.get(mine.size() - 1).getCreatedAt()) >= 0,
                "newest first");
    }

    // ── the wire ────────────────────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> list(String user, String... status) throws Exception {
        String path = "/api/v1/ai/my/confirmations" + (status.length > 0 ? "?status=" + status[0] : "");
        HttpResponse<String> response = get(path, user);
        assertEquals(200, response.statusCode(), "the list must be served: " + response.body());
        return Envelopes.data(Json.parse(response.body()));
    }

    private String pendingWriteFor(String user) throws Exception {
        HttpResponse<String> response = post("/api/v1/ai/chat", user,
                "{\"message\":\"" + WRITE_THAT_WAITS + "\",\"customerId\":" + customerId + "}");
        Map<String, Object> turn = Envelopes.data(Json.parse(response.body()));
        assertEquals("pending_confirmation", turn.get("status"), "the write must wait: " + turn);
        return String.valueOf(turn.get("token"));
    }

    private Map<String, Object> decide(String user, String token, String decision) throws Exception {
        HttpResponse<String> response = post("/api/v1/ai/my/confirmations/" + token + "/decide", user,
                "{\"decision\":\"" + decision + "\"}");
        assertEquals(200, response.statusCode(), "a decision is answered: " + response.body());
        return Envelopes.data(Json.parse(response.body()));
    }

    private HttpResponse<String> post(String path, String user, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestTokens.forUser(user, delegationSecret))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String user) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + TestTokens.forUser(user, delegationSecret))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String statusOf(String token) {
        return confirmations.findByToken(token).orElseThrow().getStatus();
    }
}
