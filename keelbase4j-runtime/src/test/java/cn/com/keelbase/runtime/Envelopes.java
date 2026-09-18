// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import java.util.Map;

/**
 * Unwraps the frozen {@code api-response} envelope in tests (F4 / JV-13 L2).
 *
 * <p>Every REST response now carries {@code {code, message, data, timestamp}}, so a test asserting
 * on what an endpoint <em>means</em> has to read through {@code data}. Keeping that in one place
 * stops each test from spelling out the envelope, and keeps the assertions about the contract
 * object rather than about the wrapper.
 */
final class Envelopes {

    private Envelopes() {
    }

    /** The {@code data} of an envelope body, cast to whatever the caller expects. */
    @SuppressWarnings("unchecked")
    static <T> T data(Object envelopeBody) {
        Map<String, Object> envelope = (Map<String, Object>) envelopeBody;
        if (!envelope.containsKey("data")) {
            throw new AssertionError("response is not a wire envelope: " + envelope.keySet());
        }
        return (T) envelope.get("data");
    }
}
