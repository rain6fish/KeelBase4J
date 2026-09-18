// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * JV-13 L1 — the F5 frontend contract surface ({@code conformance-profile.md} §2.2).
 *
 * <p>These two endpoints are what let one frontend serve either runtime: it branches on the
 * capability list, never on which runtime answered (ADR-0002 Rev-8 / FE-1). Two properties are
 * therefore load-bearing and asserted here:
 *
 * <ul>
 *   <li><b>Reachable without a token.</b> The frontend has to learn what this system offers before
 *       anyone holds one — if these demanded authentication the frontend could not decide what to
 *       render at all.
 *   <li><b>The frozen shape.</b> The payload matches {@code capabilities.schema.json} /
 *       {@code app-provenance.schema.json} field for field. A shape that drifts silently is worse
 *       than a missing endpoint, because the frontend keeps rendering and keeps being wrong.
 * </ul>
 *
 * <p>These assertions are structural rather than schema-driven: the runtime takes no JSON-schema
 * dependency, and the schema-level judge is the language-neutral Full-profile runner in the main
 * repo, which validates this same payload against the real schemas. What is proven here is the
 * contract shape and the public reachability; what is proven there is conformance to the frozen
 * files.
 *
 * <p>Responses now arrive in the {@code api-response} envelope (F4 / JV-13 L2), so the assertions
 * read through {@code data}. That the envelope is present at all is asserted once, in {@link #get}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AppContractTest {

    private static final Set<String> PRESETS = Set.of("full", "small", "lite");

    @Autowired
    TestRestTemplate rest;

    @Test
    void capabilitiesIsReachableWithoutAToken() {
        Map<String, Object> body = get("/api/v1/app/capabilities");
        assertTrue(PRESETS.contains(body.get("preset")),
                "preset must be one of the frozen enum, was: " + body.get("preset"));
    }

    @Test
    void capabilitiesCarriesTheFourFrozenFields() {
        Map<String, Object> body = get("/api/v1/app/capabilities");
        assertEquals(Set.of("preset", "features", "ai", "businessModules"), body.keySet(),
                "the contract is additionalProperties:false — no extra fields, none missing");
    }

    @Test
    void capabilitiesAiDistinguishesEnabledFromConfigured() {
        Map<String, Object> ai = assertInstanceOf(Map.class, get("/api/v1/app/capabilities").get("ai"));
        assertEquals(Set.of("enabled", "providerConfigured", "provider"), ai.keySet());

        // This runtime ships the AI seam with no provider bound to it, and that difference is the
        // whole reason the contract carries both flags.
        assertEquals(Boolean.TRUE, ai.get("enabled"));
        assertEquals(Boolean.FALSE, ai.get("providerConfigured"));
    }

    @Test
    void capabilitiesBusinessModulesAreProjectedInTheFrozenShape() {
        Map<String, Object> body = get("/api/v1/app/capabilities");
        List<?> modules = assertInstanceOf(List.class, body.get("businessModules"));
        assertTrue(!modules.isEmpty(), "a deployment that declares no modules renders no navigation");

        for (Object entry : modules) {
            Map<?, ?> module = assertInstanceOf(Map.class, entry);
            assertEquals(Set.of("id", "label", "description"), module.keySet(),
                    "each module is exactly {id,label,description} — additionalProperties:false");
        }
    }

    @Test
    void provenanceIsReachableWithoutATokenAndCarriesSourceAndRuntime() {
        Map<String, Object> body = get("/api/v1/app/provenance");
        assertEquals(Set.of("source", "runtime"), body.keySet());

        assertInstanceOf(Map.class, body.get("source"));
        Map<String, Object> runtime = assertInstanceOf(Map.class, body.get("runtime"));
        assertEquals(Set.of("preset", "businessModules", "aiToolFingerprint"), runtime.keySet());
    }

    @Test
    void provenanceToolFingerprintCountsToolsWithoutDisclosingThem() {
        Map<String, Object> runtime = assertInstanceOf(Map.class, get("/api/v1/app/provenance").get("runtime"));
        Map<String, Object> fingerprint =
                assertInstanceOf(Map.class, runtime.get("aiToolFingerprint"));

        assertEquals(Set.of("total", "readTools", "writeTools"), fingerprint.keySet(),
                "counts only — tool names, risk levels and revoke classes stay server-side");

        int total = ((Number) fingerprint.get("total")).intValue();
        int reads = ((Number) fingerprint.get("readTools")).intValue();
        int writes = ((Number) fingerprint.get("writeTools")).intValue();
        assertEquals(total, reads + writes, "every tool is either a reader or a writer");
        assertTrue(total > 0, "the runtime registers tools, so the fingerprint is not empty");
    }

    @Test
    void capabilitiesModuleTextSurvivesTheWireIntact() {
        Map<String, Object> body = get("/api/v1/app/capabilities");
        List<?> modules = assertInstanceOf(List.class, body.get("businessModules"));
        Map<?, ?> crm = assertInstanceOf(Map.class, modules.get(0));

        // These are the strings the frontend renders, so this asserts the text itself rather than
        // merely that a field exists: a value can arrive structurally perfect and still be mojibake
        // (that is exactly what a literal non-ASCII value in .properties produces).
        assertEquals("crm", crm.get("id"));
        assertEquals("AI CRM", crm.get("label"));
        assertEquals("客户、跟进与风险分析", crm.get("description"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = rest.exchange(URI.create(path), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        assertEquals(200, response.getStatusCode().value(),
                path + " must be reachable without a delegation token");

        // Responses go out in the frozen api-response envelope, so the payload is `data`. Unwrapping
        // here rather than in each test keeps the assertions about the contract object itself.
        Map<String, Object> envelope = (Map<String, Object>) response.getBody();
        assertEquals(Set.of("code", "message", "data", "timestamp"), envelope.keySet(),
                "every REST response carries the four frozen envelope keys");
        return (Map<String, Object>) envelope.get("data");
    }
}
