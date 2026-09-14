// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * CE-1 / G0 conformance: reproduce {@code canonical-json-v1-vector.json} with the Java
 * implementation. Reads the same vector file as the reference implementation.
 */
class CanonicalJsonTest {

    @TestFactory
    List<DynamicTest> canonicalVectors() {
        Map<String, Object> doc = Vectors.map(Vectors.read("canonical-json-v1-vector.json"));
        List<Object> cases = Vectors.list(doc, "cases");
        return Vectors.dynamic(cases, c -> {
            Map<String, Object> tc = Vectors.map(c);
            String id = Vectors.str(tc, "id");
            String expected = Vectors.str(tc, "canonicalBytes");
            String actual = CanonicalJson.canonical(tc.get("input"));
            assertEquals(expected, actual, id + ": canonical bytes");
        });
    }
}
