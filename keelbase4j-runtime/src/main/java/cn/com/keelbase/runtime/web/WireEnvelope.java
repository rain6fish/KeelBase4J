// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the two frozen REST shapes — {@code api-response} for success and {@code error-body} for
 * failure (main repo {@code specs/protocol/schemas/v1}).
 *
 * <p>They are the same four keys deliberately: a client reads {@code code} / {@code message} /
 * {@code data} / {@code timestamp} whichever way the request went, and only {@code data} differs in
 * kind — the payload on success, {@code null} on failure. The reference's own exception filter is
 * described the same way ("与成功信封同构").
 *
 * <p>The timestamp is ISO-8601 UTC at millisecond precision with a {@code Z}, because that is what
 * the wire convention fixes and what the reference emits ({@code Date.toISOString()}).
 */
final class WireEnvelope {

    /** §3 wire 约定：ISO-8601 UTC、毫秒精度、Z 结尾。 */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private WireEnvelope() {
    }

    /** Success: {@code data} carries the payload; {@code code} is the HTTP status that was sent. */
    static Map<String, Object> success(int code, Object data) {
        return build(code, "操作成功", data);
    }

    /**
     * Failure: {@code data} is always {@code null} — the contract pins it that way, and it is what
     * keeps "there is no payload" distinguishable from "the payload happened to be empty".
     */
    static Map<String, Object> error(int code, String message) {
        return build(code, message, null);
    }

    /** Key order follows the reference (code / message / data / timestamp); the contract does not care. */
    private static Map<String, Object> build(int code, String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", data);
        body.put("timestamp", TIMESTAMP.format(Instant.now()));
        return body;
    }
}
