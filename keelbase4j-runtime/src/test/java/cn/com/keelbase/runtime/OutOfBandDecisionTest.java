// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.ConfirmationLifecycle;
import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.governance.ConfirmationRequest;
import cn.com.keelbase.runtime.governance.ConfirmationRequestRepository;
import cn.com.keelbase.runtime.governance.ConfirmationStore;
import cn.com.keelbase.runtime.identity.Principal;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Deciding a confirmation from outside the conversation — the Action Center's service side, and the
 * half of v2's offline window that makes the window worth having (ADR-0015).
 *
 * <p>What these tests are actually about is the <em>guards</em>, because the transitions are the ones
 * the in-conversation path already takes: only the row's own operator, only while it is pending, only
 * inside the offline window. Two of them decide whether this path is safe at all — a second attempt
 * must be a no-op rather than a second execution, and a window that has closed must refuse rather than
 * decide — so both are asserted on the observable thing (how many effects exist) and not only on the
 * answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "keelbase.chat.stream-wait-ms=800")
@ActiveProfiles("test")
class OutOfBandDecisionTest {

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
    void anOperatorDecidesTheirOwnConfirmationAfterLeavingTheConversation() throws Exception {
        String token = pendingWriteFor("alice");
        int before = effectsOf("alice").size();

        Map<String, Object> answer = decide("alice", token, "approve");

        assertEquals(true, answer.get("ok"), "the decision is in effect");
        assertEquals(true, answer.get("success"), "and the write it approved ran: " + answer);
        assertNotNull(answer.get("resultId"), "with the record it produced: " + answer);
        assertEquals(before + 1, effectsOf("alice").size(), "and exactly one write is on the ledger");
    }

    @Test
    void decliningWritesNothing() throws Exception {
        String token = pendingWriteFor("alice");
        int before = effectsOf("alice").size();

        Map<String, Object> answer = decide("alice", token, "decline");

        assertEquals(true, answer.get("ok"), "the decision is in effect");
        assertEquals(false, answer.get("success"), "and nothing ran");
        assertEquals(before, effectsOf("alice").size(), "nothing was written");
    }

    /**
     * The guard that makes this path safe: outside the conversation a second attempt is an ordinary
     * thing to do — a retry, a second device — so it is a no-op, and the tool still runs once.
     */
    @Test
    void aSecondDecisionIsANoOpAndRunsNothingAgain() throws Exception {
        String token = pendingWriteFor("alice");
        int before = effectsOf("alice").size();
        decide("alice", token, "approve");

        Map<String, Object> again = decide("alice", token, "approve");

        assertEquals(false, again.get("ok"), "not in effect — it was already decided: " + again);
        assertEquals("already decided", again.get("message"));
        assertEquals(before + 1, effectsOf("alice").size(),
                "a repeated decision must never execute the tool a second time");
    }

    /**
     * Somebody else's token and a token that does not exist answer the same thing, and neither moves
     * the row: telling them apart would let a caller probe which tokens exist.
     */
    @Test
    void anotherOperatorsTokenIsIndistinguishableFromAnUnknownOne() throws Exception {
        String token = pendingWriteFor("alice");

        Map<String, Object> someoneElse = decide("bob", token, "approve");
        Map<String, Object> unknown = decide("bob", "0f0f0f0f-0000-0000-0000-000000000000", "approve");

        assertEquals(false, someoneElse.get("ok"));
        assertEquals(unknown.get("ok"), someoneElse.get("ok"), "the same answer either way");
        assertEquals(unknown.get("message"), someoneElse.get("message"), "down to the message");
        assertEquals(ConfirmationLifecycle.PENDING, statusOf(token), "and the row does not move");
    }

    /**
     * Past the offline window the decision is refused and the row is left alone — the sweeper is what
     * ends it, not this path. Asserted through the store, whose window is a parameter: the engine
     * always passes the contract's, and a test cannot age a row's {@code createdAt}.
     */
    @Test
    void pastTheOfflineWindowTheDecisionIsRefusedAndNothingMoves() {
        ConfirmationRequest req = store.create(new Principal("alice", "user"), "create_followup", "{}", "R3");

        ConfirmationStore.OutOfBandResult result = store.decideOutOfBand(
                req.getToken(), new Principal("alice", "user"), ConfirmationLifecycle.APPROVE,
                Instant.now(), ALREADY_CLOSED);

        assertEquals(ConfirmationStore.OutOfBand.EXPIRED, result.outcome(),
                "a closed window refuses the decision");
        assertEquals(ConfirmationLifecycle.PENDING, statusOf(req.getToken()),
                "and leaves it where the sweeper will find it");
    }

    /**
     * A decision taken elsewhere reaches the view that is still open. The reference deliberately does
     * not do this, because its stream would run the tool; this runtime's stream only reports, so the
     * console can be told instead of showing "timed out" about something that was approved.
     */
    @Test
    void anOpenStreamIsToldAboutADecisionTakenElsewhere() throws Exception {
        BufferedReader stream = openStreamFor("alice");
        String token = firstConfirmationToken(stream);

        decide("alice", token, "approve");

        List<Map<String, Object>> events = new ArrayList<>();
        String line;
        while ((line = stream.readLine()) != null) {
            Map<String, Object> event = readEvent(line);
            if (event != null) {
                events.add(event);
            }
        }
        assertTrue(events.stream().anyMatch(e -> "confirmation_decision".equals(e.get("type"))),
                "the open stream carries the decision: " + events);
    }

    // ── the wire ────────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> decide(String user, String token, String decision) throws Exception {
        HttpResponse<String> response = post("/api/v1/ai/my/confirmations/" + token + "/decide", user,
                "{\"decision\":\"" + decision + "\"}");
        assertEquals(200, response.statusCode(), "a decision is answered, whatever it concluded");
        return Envelopes.data(Json.parse(response.body()));
    }

    /** Drive a real pending confirmation: the console's own write, waiting on a human. */
    private String pendingWriteFor(String user) throws Exception {
        HttpResponse<String> response = post("/api/v1/ai/chat", user,
                "{\"message\":\"" + WRITE_THAT_WAITS + "\",\"customerId\":" + customerId + "}");
        Map<String, Object> turn = Envelopes.data(Json.parse(response.body()));
        assertEquals("pending_confirmation", turn.get("status"), "the write must wait: " + turn);
        return String.valueOf(turn.get("token"));
    }

    /** This operator's recorded side effects, as the console lists them. */
    private List<?> effectsOf(String user) throws Exception {
        HttpResponse<String> response = get("/api/v1/ai/tool-effects?page=1&limit=20", user);
        Map<String, Object> page = Envelopes.data(Json.parse(response.body()));
        return (List<?>) page.get("items");
    }

    private BufferedReader openStreamFor(String user) throws Exception {
        String bearer = TestTokens.forUser(user, delegationSecret);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/ai/chat/stream"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"message\":\"" + WRITE_THAT_WAITS + "\",\"customerId\":" + customerId + "}",
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode(), "the stream must open");
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
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

    private static String firstConfirmationToken(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            Map<String, Object> event = readEvent(line);
            if (event != null && "confirmation_request".equals(event.get("type"))) {
                return String.valueOf(asMap(event.get("confirmation")).get("token"));
            }
        }
        throw new AssertionError("the stream ended without raising a confirmation");
    }

    private static Map<String, Object> readEvent(String line) {
        if (!line.startsWith("data:")) {
            return null;
        }
        return asMap(Json.parse(line.substring(5).trim()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        assertFalse(o == null, "expected an object");
        return (Map<String, Object>) o;
    }
}
