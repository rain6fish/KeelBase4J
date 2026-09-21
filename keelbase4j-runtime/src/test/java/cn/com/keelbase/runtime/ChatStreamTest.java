// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The streaming channel, in the two ways it ends.
 *
 * <p>The second test is the reason the stream stays open at all. A decision that the client did not
 * make — here, one made from another request while the view is open — can only arrive over this
 * channel; a stream that closed when the turn was built would leave the caller waiting for news that
 * had no way to reach it. Asserting only the local approve would test the case that did not need the
 * design.
 *
 * <p>Read incrementally, with the JDK's HTTP client, because that is what a stream is. A buffered test
 * client would wait for the whole response, which for this endpoint means waiting for a decision that
 * the test itself is supposed to make — a deadlock dressed up as a passing test.
 *
 * <p>The wait is shortened via {@code keelbase.chat.stream-wait-ms} because the contract's own window
 * is sixty seconds; the shortening is a test affordance, not the production value.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "keelbase.chat.stream-wait-ms=800")
@ActiveProfiles("test")
class ChatStreamTest {

    @LocalServerPort
    int port;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    @Test
    void aDecisionMadeWhileTheStreamIsOpenReachesIt() throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();

        try (BufferedReader reader = openStream("alice", "给客户建一条跟进记录")) {
            String line;
            while ((line = reader.readLine()) != null) {
                Map<String, Object> event = readEvent(line);
                if (event == null) {
                    continue;
                }
                events.add(event);
                if ("confirmation_request".equals(event.get("type"))) {
                    // Decided from a different request, which is the case the design exists for. This
                    // runs while the stream is still open, because the read above is blocked on it.
                    approve(tokenOf(event));
                }
            }
        }

        assertEquals(List.of("tool_start", "text", "confirmation_request",
                        "confirmation_decision", "tool_end", "done"),
                types(events),
                "the console expects these in this order; got " + events);
        Map<String, Object> decision = payload(events, "confirmation_decision");
        assertEquals(true, decision.get("approved"), "and it says what was decided");
    }

    @Test
    void aWaitThatExpiresEndsWithoutADecision() throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();

        try (BufferedReader reader = openStream("alice", "给客户建一条跟进记录")) {
            String line;
            while ((line = reader.readLine()) != null) {
                Map<String, Object> event = readEvent(line);
                if (event != null) {
                    events.add(event);
                }
            }
        }

        assertEquals(List.of("tool_start", "text", "confirmation_request", "done"), types(events),
                "the wait ran out, so the stream closes with no decision: " + events);
        // Under v2 an expired wait does not end a confirmation — the write is still decidable. Saying
        // "timeout" here would be a claim the data does not support, so no decision is claimed.
        assertFalse(types(events).contains("confirmation_decision"),
                "an expired wait is not a decision");
    }

    /**
     * The management path is the console's, and it is for administrators. carol is this deployment's
     * administrator and alice is not, so the two halves of the gate are one call apart.
     */
    @Test
    void theManagementStreamIsForAdministrators() throws Exception {
        try (InputStream refused = postStream("alice", "/admin/ai/chat/stream", "给客户建一条跟进记录")
                .body()) {
            String body = new String(refused.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"code\":403"), "a non-administrator is refused: " + body);
        }

        HttpResponse<InputStream> allowed =
                postStream("carol", "/admin/ai/chat/stream", "给客户建一条跟进记录");
        assertEquals(200, allowed.statusCode(), "an administrator may open it");
        assertTrue(allowed.headers().firstValue("content-type").orElse("").contains("text/event-stream"),
                "and gets the same stream");
        // Closing without reading aborts the request, which is the console closing the drawer.
        allowed.body().close();
    }

    private BufferedReader openStream(String user, String message) throws Exception {
        HttpResponse<InputStream> response = postStream(user, "/ai/chat/stream", message);
        assertEquals(200, response.statusCode(), "the stream must open");
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("text/event-stream"),
                "and be a stream, not a body");
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }

    private HttpResponse<InputStream> postStream(String user, String endpoint, String message)
            throws Exception {
        String bearer = TestTokens.forUser(user, delegationSecret);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1" + endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"message\":\"" + message + "\",\"customerId\":1}", StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private void approve(String token) throws Exception {
        String bearer = TestTokens.forUser("alice", delegationSecret);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/ai/confirmations/" + token))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString("{\"decision\":\"approve\"}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "the approval itself must succeed");
    }

    /** One SSE line, if it carries an event. The console reads {@code data:} and ignores the rest. */
    private static Map<String, Object> readEvent(String line) {
        if (!line.startsWith("data:")) {
            return null;
        }
        return asMap(Json.parse(line.substring(5).trim()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static List<String> types(List<Map<String, Object>> events) {
        return events.stream().map(e -> String.valueOf(e.get("type"))).toList();
    }

    private static Map<String, Object> payload(List<Map<String, Object>> events, String type) {
        return events.stream()
                .filter(e -> type.equals(e.get("type")))
                .findFirst()
                .map(e -> asMap(e.get("confirmationDecision")))
                .orElseThrow(() -> new AssertionError("no " + type + " event in " + events));
    }

    private static String tokenOf(Map<String, Object> event) {
        Map<String, Object> confirmation = asMap(event.get("confirmation"));
        assertNotNull(confirmation, "a confirmation_request carries the token the card needs");
        return String.valueOf(confirmation.get("token"));
    }
}
