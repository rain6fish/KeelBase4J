// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** G0 conformance: reproduce {@code audit-hash-v1-vector.json}. */
class AuditChainTest {

    @TestFactory
    List<DynamicTest> auditHashVectors() {
        Map<String, Object> doc = Vectors.map(Vectors.read("audit-hash-v1-vector.json"));
        return Vectors.dynamic(Vectors.list(doc, "cases"), c -> {
            Map<String, Object> tc = Vectors.map(c);
            String id = Vectors.str(tc, "id");
            switch (Vectors.str(tc, "kind")) {
                case "hex", "determinism": {
                    String actual = AuditChain.hash(
                            Vectors.str(tc, "key"), (String) tc.get("prevHash"), tc.get("payload"));
                    assertEquals(Vectors.str(tc, "expectHex"), actual, id + ": hash");
                    break;
                }
                case "tamper": {
                    String key = Vectors.str(tc, "key");
                    String a = AuditChain.hash(key, null, tc.get("payloadA"));
                    String b = AuditChain.hash(key, null, tc.get("payloadB"));
                    assertNotEquals(a, b, id + ": tampering must change the hash");
                    break;
                }
                case "genesis": {
                    String key = Vectors.str(tc, "key");
                    Object payload = tc.get("payload");
                    assertNotEquals(
                            AuditChain.hash(key, null, payload),
                            AuditChain.hash(key, "", payload),
                            id + ": 'genesis' literal must differ from the empty string");
                    break;
                }
                case "legacy": {
                    assertEquals(
                            Vectors.str(tc, "expectHex"),
                            AuditChain.legacyKey(Vectors.str(tc, "secret")),
                            id + ": legacy key derivation");
                    break;
                }
                case "chain": {
                    assertChain(tc, id);
                    break;
                }
                default:
                    throw new IllegalStateException(id + ": unknown kind " + tc.get("kind"));
            }
        });
    }

    private void assertChain(Map<String, Object> tc, String id) {
        List<String> keys = new ArrayList<>();
        for (Object k : Vectors.list(tc, "keys")) {
            keys.add((String) k);
        }
        Map<Integer, Object> overrides = new HashMap<>();
        if (tc.get("payloadOverrides") != null) {
            for (Object o : Vectors.list(tc, "payloadOverrides")) {
                Map<String, Object> ov = Vectors.map(o);
                overrides.put(((Number) ov.get("id")).intValue(), ov.get("payload"));
            }
        }
        List<AuditChain.ChainRow> rows = new ArrayList<>();
        Map<Integer, Object> payloads = new HashMap<>();
        for (Object o : Vectors.list(tc, "rows")) {
            Map<String, Object> r = Vectors.map(o);
            int rid = ((Number) r.get("id")).intValue();
            rows.add(new AuditChain.ChainRow(rid, (String) r.get("prevHash"), Vectors.str(r, "hash")));
            payloads.put(rid, overrides.getOrDefault(rid, r.get("payload")));
        }
        AuditChain.Verification v =
                AuditChain.verify(rows, keys, row -> payloads.get(row.id()));

        Map<String, Object> expect = Vectors.map(tc.get("expect"));
        assertEquals(Boolean.TRUE.equals(expect.get("valid")), v.valid(), id + ": valid");
        if (expect.get("checked") != null) {
            assertEquals(((Number) expect.get("checked")).intValue(), v.checked(), id + ": checked");
        }
        if (expect.get("brokenIndex") != null) {
            assertEquals(((Number) expect.get("brokenIndex")).intValue(), v.brokenIndex(), id + ": brokenIndex");
        }
        if (v.valid()) {
            assertTrue(v.brokenIndex() == null, id + ": no brokenIndex when valid");
        }
    }
}
