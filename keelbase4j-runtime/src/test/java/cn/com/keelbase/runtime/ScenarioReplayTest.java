// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.protocol.Vectors;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * JV-15 Slice 1 — the neutral replay corpus, replayed on the second carrier.
 *
 * <p>The main repo's scenario packs carry a {@code replay} per step: a step sequence written in
 * <b>wire objects</b> (a tool name, a wire-object id) rather than in paths, so any conformant runtime
 * can execute it and reach the same expectations — {@code docs/protocols/conformance-profile.md}
 * §2.4, Extended layer. This test is <em>this</em> runtime's runner: it reads the packs from the
 * vendored snapshot and drives them over the same HTTP boundary a real caller uses.
 *
 * <p><b>What this test is not.</b> It is not the criterion — the corpus is. A runner is allowed to
 * know how to translate ("each runtime maps the objects to its own endpoints"); what it must not do
 * is quietly weaken an expectation. So every entry is either replayed and its {@code expect}
 * asserted, or recorded here with the reason it cannot be replayed, and the inventory test keeps that
 * honest: a new entry in the corpus turns this class red until it is classified, and each replay test
 * asserts the exact set of entries it exercised.
 *
 * <p><b>The mapping this runtime needs</b> (all of it runner-side):
 * <ul>
 *   <li>{@code call.tool} — there is no "call a tool by name" endpoint here: a tool is reached by a
 *       message the planner routes ({@code POST /ai/chat}). The routing table is {@link #TOOL_MESSAGES},
 *       and one tool is even named differently here ({@code create_followup_task} is
 *       {@code create_followup}) — both halves are asserted against what the runtime reports, not
 *       assumed.</li>
 *   <li>{@code call.read} — wire object id → endpoint: {@code audit-chain-verification} →
 *       {@code GET /audit/verify}.</li>
 *   <li>{@code call.write} — {@code side-effect-revoke#revoke} → {@code DELETE /ai/tool-effects/{id}}.
 *       The confirmation flow is <b>not in replay scope</b>: its token and its decision object are
 *       transport / implementation freedom — a stream on one side, a JSON response on the other — so the
 *       corpus does not carry them and this runner needs no mapping for them. See the pack's {@code note}
 *       and {@code conformance-profile.md} §2.4.</li>
 *   <li>a tool call's {@code expect} is relative to {@code tool-invocation.response}: this runtime
 *       answers an {@code ExecutionOutcome} whose {@code status} carries the same facts
 *       ({@code executed} ⇔ {@code status=executed}; {@code requiresConfirmation} ⇔
 *       {@code status=pending_confirmation}).</li>
 *   <li>{@code expect: null} — the object is not available to this actor: the revoke is refused
 *       (403) instead of returned.</li>
 *   <li>{@code given.fixtures} — {@code crm-customer} has no create endpoint here, so the row is
 *       seeded through the repository (the one place this runner goes around HTTP, recorded rather
 *       than hidden); {@code crm-order} has no counterpart at all — there is no Order entity — and no
 *       replayed entry consumes it.</li>
 *   <li>{@code {"$ref":"customer.id"}} — resolved to the fixture the previous step seeded.</li>
 * </ul>
 *
 * <p>The mapping below is written down for third parties in {@code docs/wire-object-endpoints.md} —
 * together with the objects this runtime does <em>not</em> expose — so that a runtime's answer can be
 * checked rather than inferred from this file.
 *
 * <p><b>Not replayable here, with the reason</b> (asserted by the inventory test): the tools
 * {@code delete_customer} / {@code create_event} / {@code query_events} (this runtime registers two
 * tools — {@code analyze_customer_risk}, {@code create_followup}; those three belong to the
 * <em>reference application's</em> inventory, and an inventory is an application's face, not the
 * contract's. The packs <b>declare</b> the tools they assume — the pack's {@code tools} — so a
 * consumer can read "not applicable" off the corpus instead of off this runner's classification) · {@code read: permission-decision} (this runtime computes the frozen decision but
 * exposes only the capability list, never a decision over HTTP) · {@code read: evidence-package}
 * (no evidence-root export here) · the governance view over {@code side-effect-revoke} (no such
 * endpoint, and this runtime's only local target is {@code follow_up}, so {@code resultType: crm_task}
 * could not hold even if it existed).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class ScenarioReplayTest {

    /**
     * The corpus names a tool; this runtime routes a message. Both halves are here on purpose: the key
     * is the corpus's name, the value is what this deployment's planner understands.
     */
    private static final Map<String, String> TOOL_MESSAGES = Map.of(
            "analyze_customer_risk", "分析客户风险",
            "create_followup_task", "创建跟进任务");

    /** What this runtime calls the same tool — asserted after the call, so a rename cannot pass. */
    private static final Map<String, String> TOOL_LOCAL_NAMES = Map.of(
            "analyze_customer_risk", "analyze_customer_risk",
            "create_followup_task", "create_followup");

    /**
     * The corpus names an actor; this deployment maps it to a local identity. Tier A declares
     * `alice` / `bob` / `carol` — there is no one called `admin` here, and the corpus's `admin`
     * means "whoever may do this", so it lands on this deployment's manager.
     */
    private static final Map<String, String> ACTOR_DIRECTORY = Map.of(
            "alice", "alice",
            "bob", "bob",
            "admin", "carol");

    private static final Set<String> GOLDEN_REPLAYED = Set.of(
            "risk_analysis", "create_followup_task_confirmation",
            "audit_verifiable", "revoke_effect", "ownership_check");
    private static final Set<String> GOLDEN_UNSERVABLE = Set.of("governance_view");

    private static final Set<String> TRUST_REPLAYED = Set.of("revoke_effect");
    private static final Set<String> TRUST_UNSERVABLE = Set.of("cross_user_denied", "r5_blocked", "evidence_root");

    private static final Set<String> CROSS_ENTRY_UNSERVABLE = Set.of(
            "mcp_r5_deny", "deny_reasons_same_source", "mcp_r3_confirmation", "allow_snapshot_same_shape");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    CustomerRepository customers;

    @Value("${keelbase.delegation.secret}")
    String delegationSecret;

    /** State a replay run carries between steps — the corpus expresses targets as objects, not as ids. */
    private static final class Run {
        String actor = "alice";
        Long customerId;
        String token;
        Long effectId;
        final Set<String> replayed = new LinkedHashSet<>();
        final Set<String> unservable = new LinkedHashSet<>();
    }

    @Test
    void goldenApplication_replaysWhatThisRuntimeServes() {
        Run run = new Run();
        List<Object> steps = steps(pack("golden-application-v1.json"));
        // Step 1 `customer` is its fixtures: this runtime has no customer-create endpoint, so the row
        // is seeded the way the acceptance test seeds one.
        run.customerId = customers.save(new Customer("瀚宇制造", "high", "alice")).getId();
        seedEffect(run);

        for (String key : keysInDocumentOrder(steps, GOLDEN_REPLAYED, GOLDEN_UNSERVABLE)) {
            replayStep(steps, key, run);
        }

        assertEquals(GOLDEN_REPLAYED, run.replayed,
                "every replayable golden step must actually run; recorded unservable: " + run.unservable);
        assertEquals(GOLDEN_UNSERVABLE, run.unservable, "unservable steps are recorded, not skipped");
    }

    @Test
    void trustProof_replaysWhatThisRuntimeServes() {
        Run run = new Run();
        List<Object> scenarios = steps(pack("trust-proof-v1.json"));
        run.customerId = customers.save(new Customer("瀚宇制造", "high", "alice")).getId();
        seedEffect(run);

        for (String key : keysInDocumentOrder(scenarios, TRUST_REPLAYED, TRUST_UNSERVABLE)) {
            replayStep(scenarios, key, run);
        }

        assertEquals(TRUST_REPLAYED, run.replayed,
                "every replayable trust-proof scenario must actually run; recorded unservable: " + run.unservable);
        assertEquals(TRUST_UNSERVABLE, run.unservable, "unservable scenarios are recorded, not skipped");
    }

    /**
     * The steps to visit, in the corpus's own order.
     *
     * <p>Order is not cosmetic here: a step that revokes the side effect an earlier step produced can
     * only run after it, so iterating a {@code Set} would fail for reasons that have nothing to do with
     * the runtime.
     */
    private static List<String> keysInDocumentOrder(List<Object> steps, Set<String> replayed, Set<String> unservable) {
        List<String> keys = new ArrayList<>();
        for (Object step : steps) {
            String key = String.valueOf(Vectors.map(step).get("key"));
            if (replayed.contains(key) || unservable.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    @Test
    void crossEntry_hasNothingThisRuntimeCanServe() {
        Run run = new Run();
        List<Object> steps = steps(pack("cross-entry-v1.json"));
        for (String key : CROSS_ENTRY_UNSERVABLE) {
            replayStep(steps, key, run);
        }
        // Not a failure — a classification. This pack exercises the *reference application's* tools;
        // this runtime registers none of them, and a tool inventory is an application's face.
        assertEquals(Set.of(), run.replayed, "this pack must exercise nothing here");
        assertEquals(CROSS_ENTRY_UNSERVABLE, run.unservable, "all four steps must be recorded as unservable");
    }

    /** Every corpus entry is either replayed above or recorded above — nothing may fall between. */
    @Test
    void corpusInventory_isFullyClassified() {
        Set<String> entries = new LinkedHashSet<>();
        for (String file : List.of("golden-application-v1.json", "trust-proof-v1.json", "cross-entry-v1.json")) {
            for (Object step : steps(pack(file))) {
                Map<String, Object> s = Vectors.map(step);
                String key = String.valueOf(s.get("key"));
                Object replay = s.get("replay");
                if (replay instanceof List<?> list) {
                    for (int i = 0; i < list.size(); i++) {
                        entries.add(file + "#" + key + "[" + i + "]");
                    }
                } else {
                    entries.add(file + "#" + key + " (replay: " + replay + ")");
                }
            }
        }

        long replayed = entries.stream().filter(e -> e.contains("[")).count();
        assertEquals(14, replayed, "the corpus's replay entries changed — re-classify before trusting this runner");

        Set<String> classified = new LinkedHashSet<>();
        for (String id : Set.of(
                "golden-application-v1.json#risk_analysis[0]",
                "golden-application-v1.json#create_followup_task_confirmation[0]",
                "golden-application-v1.json#audit_verifiable[0]",
                "golden-application-v1.json#revoke_effect[0]",
                "golden-application-v1.json#ownership_check[0]",
                "trust-proof-v1.json#revoke_effect[0]",
                "golden-application-v1.json#governance_view[0]",
                "trust-proof-v1.json#cross_user_denied[0]",
                "trust-proof-v1.json#r5_blocked[0]",
                "trust-proof-v1.json#evidence_root[0]",
                "cross-entry-v1.json#mcp_r5_deny[0]",
                "cross-entry-v1.json#deny_reasons_same_source[0]",
                "cross-entry-v1.json#mcp_r3_confirmation[0]",
                "cross-entry-v1.json#allow_snapshot_same_shape[0]")) {
            classified.add(id);
        }

        Set<String> unclassified = new LinkedHashSet<>(entries);
        unclassified.removeAll(classified);
        assertEquals(Set.of(), unclassified, "these corpus entries are neither replayed nor recorded as unservable");
    }

    // ── the replay itself ────────────────────────────────────────────────────────────────────────

    private void replayStep(List<Object> steps, String key, Run run) {
        Map<String, Object> step = steps.stream()
                .map(Vectors::map)
                .filter(s -> key.equals(s.get("key")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the corpus has no step `" + key + "`"));

        actor(step, run);
        for (Object e : list(step.get("replay"))) {
            Map<String, Object> entry = Vectors.map(e);
            try {
                assertExpect(entry.get("expect"), replay(entry, run), "replay of " + key);
            } catch (UnservableException unservable) {
                run.unservable.add(key);
                return;
            }
        }
        if (!list(step.get("replay")).isEmpty()) {
            run.replayed.add(key);
        }
    }

    /**
     * The write the revoke steps act on.
     *
     * <p>A step cannot yet name a previous step's product (Slice 0's ambiguity 5), and the corpus no
     * longer carries the approve entry that used to produce it — that object is on neither
     * implementation's response. So the runner establishes the effect the way a caller can: a gated
     * write over the wire, then the decision. The corpus says nothing about it, and cannot.
     */
    private void seedEffect(Run run) {
        Turn turn = turn(run, "创建跟进任务", run.customerId);
        assertNotNull(turn.token(), "a write tool must be gated, or there is nothing to decide");
        run.token = turn.token();
        confirm(run);
        assertNotNull(run.effectId, "the approved write must record a side effect");
    }

    /** {@code given.actor} — the corpus makes identity explicit; the runner honours it per step. */
    private void actor(Map<String, Object> step, Run run) {
        Object given = step.get("given");
        if (given != null) {
            Object declared = Vectors.map(given).get("actor");
            assertNotNull(declared, "a `given` must name its actor");
            run.actor = String.valueOf(declared);
        }
    }

    private Map<String, Object> replay(Map<String, Object> entry, Run run) {
        Map<String, Object> call = Vectors.map(entry.get("call"));
        if (call.containsKey("tool")) {
            return tool(call, run);
        }
        if (call.containsKey("read")) {
            return read(String.valueOf(call.get("read")), run);
        }
        return write(String.valueOf(call.get("write")), String.valueOf(call.get("op")), run);
    }

    private Map<String, Object> tool(Map<String, Object> call, Run run) {
        String name = String.valueOf(call.get("tool"));
        String message = TOOL_MESSAGES.get(name);
        if (message == null) {
            throw new UnservableException("tool `" + name + "` is not registered in this runtime");
        }
        Turn turn = turn(run, message, customerId(call, run));
        if (turn.tool() != null) {
            assertEquals(TOOL_LOCAL_NAMES.get(name), turn.tool(),
                    "this runtime's name for `" + name + "` changed — the mapping must follow the runtime");
        }
        if (turn.token() != null) {
            run.token = turn.token();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("executed", turn.executed());
        response.put("requiresConfirmation", turn.requiresConfirmation());
        return response;
    }

    /** N1: {@code {"$ref":"<fixture>.<field>"}} names a value an earlier step's fixture produced. */
    private Long customerId(Map<String, Object> call, Run run) {
        Object args = call.get("args");
        if (args == null) {
            return run.customerId;
        }
        Object id = Vectors.map(args).get("customerId");
        if (id instanceof Map<?, ?> ref) {
            assertEquals("customer.id", String.valueOf(ref.get("$ref")),
                    "the corpus references a fixture this runner does not know");
            assertNotNull(run.customerId, "the `customer` fixture must exist before it is referenced");
            return run.customerId;
        }
        return id instanceof Number n ? n.longValue() : run.customerId;
    }

    private Map<String, Object> read(String object, Run run) {
        return switch (object) {
            case "audit-chain-verification" -> Map.of("valid", get("/audit/verify", run.actor).get("valid"));
            default -> throw new UnservableException("this runtime exposes no wire object `" + object + "`");
        };
    }

    private Map<String, Object> write(String object, String op, Run run) {
        return switch (object + "#" + op) {
            case "side-effect-revoke#revoke" -> revoke(run);
            default -> throw new UnservableException("this runtime exposes no `" + op + "` on `" + object + "`");
        };
    }

    private Map<String, Object> revoke(Run run) {
        if (run.effectId == null) {
            throw new UnservableException("no side effect for this step to revoke");
        }
        ResponseEntity<Map> res = rest.exchange("/ai/tool-effects/" + run.effectId, HttpMethod.DELETE,
                entity(run.actor, null), Map.class);
        int status = res.getStatusCode().value();
        if (status == 403 || status == 404) {
            // Not this actor's object — the corpus's `expect: null` ("the object is not readable").
            return null;
        }
        assertEquals(200, status, "DELETE /ai/tool-effects/" + run.effectId);
        Map<String, Object> body = Envelopes.data(res.getBody());
        // Read straight off the frozen `revokeResult`'s field — not derived from `revokeStatus`:
        // a test that derives the value cannot tell whether the object actually carries it.
        return Map.of("revoked", Boolean.TRUE.equals(body.get("revoked")));
    }

    /** {@code expect} is checked key by key against what the entry's target object reports. */
    private void assertExpect(Object expect, Map<String, Object> observed, String where) {
        if (expect == null) {
            assertNull(observed, where + ": `expect: null` means the object is not readable");
            return;
        }
        assertNotNull(observed, where + ": the runtime reported nothing for a positive expectation");
        for (Map.Entry<String, Object> field : Vectors.map(expect).entrySet()) {
            assertEquals(field.getValue(), observed.get(field.getKey()), where + ": field `" + field.getKey() + "`");
        }
    }

    // ── HTTP, the way a caller reaches this runtime ──────────────────────────────────────────────

    /** What one streamed turn reported: the tool it used, and the two facts the corpus asks about. */
    private record Turn(String tool, boolean executed, boolean requiresConfirmation, String token) {
    }

    /**
     * One turn, over the channel that reports what happened.
     *
     * <p>The non-streaming answer is the frozen {@code chat-response} and carries no outcome at all
     * (JV-52 片 2), so a corpus runner that read {@code status} off it would be reading a field the
     * object does not declare. Here the turn's facts are events: {@code tool_start} names the tool,
     * {@code tool_end} says whether it ran, and {@code confirmation_request} carries the token of a
     * write that is waiting. The reference reports the same turn the same way — {@code docs/wire-object-endpoints.md}
     * declares a tool call's result as answered on <em>either</em> chat shape — so this is the channel a
     * caller that needs the outcome uses on either runtime.
     *
     * <p>Not the reply text: the replier is a replaceable bean (a model answers in prose), so a fact a
     * test derives from its wording would stop being a fact the moment a deployment swapped it.
     *
     * 一个回合，走**报出发生了什么**的那条通道。
     *
     * <p>非流式答案是冻结的 `chat-response`、**一点结果都不带**（JV-52 片 2），所以从它上面读 {@code status}
     * 的语料 runner 读的是一个**对象没有声明的字段**。这里，一个回合的事实是**事件**：`tool_start` 点出工具、
     * `tool_end` 说它跑没跑、`confirmation_request` 带着那笔等待中的写的 token。参照实现报同一个回合走的是同
     * 一条路 —— `docs/wire-object-endpoints.md` 把一次工具调用的结果声明为**两种聊天形状**都答 —— 所以在
     * **任一**运行时上需要结果的调用方都走它。
     *
     * <p>**不是** reply 的文字：replier 是**可替换的 bean**（模型用散文作答），故一个从措辞里推出来的事实，
     * 在部署换掉它的那一刻就不再是事实了。
     */
    private Turn turn(Run run, String message, Long customerId) {
        String actor = ACTOR_DIRECTORY.getOrDefault(run.actor, run.actor);
        String payload = "{\"message\":\"" + message + "\""
                + (customerId == null ? "" : ",\"customerId\":" + customerId) + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(rest.getRootUri() + "/ai/chat/stream"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestTokens.forUser(actor, delegationSecret))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        String tool = null;
        boolean executed = false;
        boolean requiresConfirmation = false;
        String token = null;
        try {
            HttpResponse<InputStream> response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode(), "the turn's stream must open");
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    Map<String, Object> event = Vectors.map(Json.parse(line.substring(5).trim()));
                    switch (String.valueOf(event.get("type"))) {
                        case "tool_start" -> tool =
                                String.valueOf(Vectors.map(event.get("toolStart")).get("name"));
                        case "tool_end" -> executed = Boolean.TRUE.equals(
                                Vectors.map(event.get("toolEnd")).get("success"));
                        case "confirmation_request" -> {
                            requiresConfirmation = true;
                            token = String.valueOf(
                                    Vectors.map(event.get("confirmation")).get("token"));
                        }
                        default -> {
                            // `text` and `done` say nothing this runner asserts about.
                        }
                    }
                    // A gated write waits on a person, and the waiting is not this runner's to sit
                    // through: it has the token, and the decision is a step of its own.
                    if (requiresConfirmation || "done".equals(String.valueOf(event.get("type")))) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            throw new AssertionError("a turn's stream could not be read: " + e, e);
        }
        return new Turn(tool, executed, requiresConfirmation, token);
    }

    private Map<String, Object> confirm(Run run) {
        Map<String, Object> body = post("/ai/confirmations/" + run.token, run.actor, Map.of("decision", "approve"));
        run.token = null;
        if (body.get("effectId") instanceof Number id) {
            run.effectId = id.longValue();
        }
        return body;
    }

    private Map<String, Object> post(String path, String actor, Object body) {
        ResponseEntity<Map> res = rest.postForEntity(path, entity(actor, body), Map.class);
        assertEquals(200, res.getStatusCode().value(), "POST " + path);
        return Envelopes.data(res.getBody());
    }

    private Map<String, Object> get(String path, String actor) {
        ResponseEntity<Map> res = rest.exchange(path, HttpMethod.GET, entity(actor, null), Map.class);
        assertEquals(200, res.getStatusCode().value(), "GET " + path);
        return Envelopes.data(res.getBody());
    }

    private HttpEntity<Object> entity(String actor, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(ACTOR_DIRECTORY.getOrDefault(actor, actor), delegationSecret));
        return new HttpEntity<>(body, headers);
    }

    // ── corpus ───────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> pack(String file) {
        return Vectors.map(Vectors.read("scenarios/" + file));
    }

    private static List<Object> steps(Map<String, Object> pack) {
        Object container = pack.containsKey("steps") ? pack.get("steps") : pack.get("scenarios");
        return list(container);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
    }

    /** An entry this runtime cannot serve. Recorded and asserted — never a silent skip. */
    private static final class UnservableException extends RuntimeException {
        UnservableException(String message) {
            super(message);
        }
    }
}
