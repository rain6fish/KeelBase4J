// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.com.keelbase.protocol.Vectors;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
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
 *   <li>{@code call.write} — {@code confirmation-decision#approve} →
 *       {@code POST /ai/confirmations/{token}}; {@code side-effect-revoke#revoke} →
 *       {@code DELETE /ai/tool-effects/{id}}.</li>
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
 * <p><b>Not replayable here, with the reason</b> (asserted by the inventory test): the tools
 * {@code delete_customer} / {@code create_event} / {@code query_events} (this runtime registers two
 * tools — {@code analyze_customer_risk}, {@code create_followup}; those three belong to the
 * <em>reference application's</em> inventory, and an inventory is an application's face, not the
 * contract's) · {@code read: permission-decision} (this runtime computes the frozen decision but
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

    private static final Set<String> GOLDEN_REPLAYED = Set.of(
            "risk_analysis", "create_followup_task_confirmation", "confirmed_write",
            "audit_verifiable", "revoke_effect", "ownership_check");
    private static final Set<String> GOLDEN_UNSERVABLE = Set.of("governance_view");

    private static final Set<String> TRUST_REPLAYED = Set.of("r3_confirmation", "revoke_effect");
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

        // S4 decides a pending confirmation. The corpus's step is the decision alone: the pending write
        // it decides is produced here, because a step cannot yet name a previous step's product
        // (JV-15 Slice 0's ambiguity 5 — preconditions are not expressible).
        Map<String, Object> pending = chat(run, "创建跟进任务", run.customerId);
        run.token = (String) pending.get("token");
        assertNotNull(run.token, "the write must be gated, or there is nothing for S4 to approve");

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
        assertEquals(16, replayed, "the corpus's replay entries changed — re-classify before trusting this runner");

        Set<String> classified = new LinkedHashSet<>();
        for (String id : Set.of(
                "golden-application-v1.json#risk_analysis[0]",
                "golden-application-v1.json#create_followup_task_confirmation[0]",
                "golden-application-v1.json#confirmed_write[0]",
                "golden-application-v1.json#audit_verifiable[0]",
                "golden-application-v1.json#revoke_effect[0]",
                "golden-application-v1.json#ownership_check[0]",
                "trust-proof-v1.json#r3_confirmation[0]",
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
        Map<String, Object> body = chat(run, message, customerId(call, run));
        if (body.get("toolCalls") instanceof List<?> called && !called.isEmpty()) {
            assertEquals(TOOL_LOCAL_NAMES.get(name), String.valueOf(called.get(0)),
                    "this runtime's name for `" + name + "` changed — the mapping must follow the runtime");
        }
        String status = String.valueOf(body.get("status"));
        if (body.get("token") != null) {
            run.token = (String) body.get("token");
        }
        if (body.get("effectId") instanceof Number id) {
            run.effectId = id.longValue();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("executed", "executed".equals(status));
        response.put("requiresConfirmation", "pending_confirmation".equals(status));
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
            case "confirmation-decision#approve" -> {
                if (run.token == null) {
                    throw new UnservableException("no pending confirmation for this step to decide");
                }
                Map<String, Object> body = confirm(run);
                String status = String.valueOf(body.get("status"));
                yield Map.of("decision", "executed".equals(status) ? "approve" : status);
            }
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
        return Map.of("revoked", "revoked".equals(body.get("revokeStatus")));
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

    private Map<String, Object> chat(Run run, String message, Long customerId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        if (customerId != null) {
            body.put("customerId", customerId);
        }
        Map<String, Object> data = post("/ai/chat", run.actor, body);
        // A pending write's token and an executed write's effect id are what the next step needs; the
        // runner carries them forward, which is what the corpus's object-level form leaves to it.
        if (data.get("token") != null) {
            run.token = (String) data.get("token");
        }
        if (data.get("effectId") instanceof Number id) {
            run.effectId = id.longValue();
        }
        return data;
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
        headers.setBearerAuth(TestTokens.forUser(actor, delegationSecret));
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
