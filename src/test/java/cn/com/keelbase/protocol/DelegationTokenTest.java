// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** G0 conformance: reproduce {@code delegation-token-v1-vector.json} (relative-time construction). */
class DelegationTokenTest {

    @TestFactory
    List<DynamicTest> delegationVectors() {
        Map<String, Object> doc = Vectors.map(Vectors.read("delegation-token-v1-vector.json"));
        return Vectors.dynamic(Vectors.list(doc, "cases"), c -> {
            Map<String, Object> tc = Vectors.map(c);
            String id = Vectors.str(tc, "id");
            String secret = Vectors.str(tc, "secret");
            Map<String, Object> claims = Vectors.map(tc.get("claims"));
            Map<String, Object> lifetime = Vectors.map(tc.get("lifetime"));
            Map<String, Object> verifier = Vectors.map(tc.get("verifier"));
            Map<String, Object> expect = Vectors.map(tc.get("expect"));

            long now = Instant.now().getEpochSecond();
            long iat = now - ((Number) lifetime.get("iatBeforeSec")).longValue();
            long exp = now + ((Number) lifetime.get("expAfterSec")).longValue();

            String token = DelegationToken.sign(claims, iat, exp, secret);
            if ("payload".equals(tc.get("mutate"))) {
                String[] parts = token.split("\\.", -1);
                token = parts[0] + "." + DelegationToken.b64Url("{\"x\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        + "." + parts[2];
            }

            String audience = Vectors.str(verifier, "audience");
            DelegationToken.Result r = DelegationToken.verify(token, secret, audience, now);

            assertEquals(Boolean.TRUE.equals(expect.get("ok")), r.ok(), id + ": ok (" + r.reason() + ")");
            if (expect.get("sub") != null) {
                assertEquals(Vectors.str(expect, "sub"), r.payload().get("sub"), id + ": sub");
            }
            if (expect.get("subPrefix") != null) {
                assertTrue(String.valueOf(r.payload().get("sub")).startsWith(Vectors.str(expect, "subPrefix")),
                        id + ": sub prefix");
            }
        });
    }
}
