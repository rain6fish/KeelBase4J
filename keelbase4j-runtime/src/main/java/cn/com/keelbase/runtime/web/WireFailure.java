// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * What the runtime says about a failure — the message and the NC-2 actionable fields
 * ({@code reason} / {@code nextStep}).
 *
 * <p>Shared by the two places a failure is rendered (the container's error dispatch and the
 * deliberate exceptions) so a 403 reads the same however it was produced. Two copies of this text
 * would be two answers to the same question.
 *
 * <p>Server faults deliberately say nothing useful: the status is honest and the message is not,
 * because a 5xx message is the classic place internal detail escapes. They also carry no
 * {@code reason} / {@code nextStep} — an actionable field on a fault the caller cannot act on is a
 * promise the runtime cannot keep.
 */
final class WireFailure {

    private WireFailure() {
    }

    /** The human-readable message for a status. */
    static String messageFor(HttpStatus status) {
        if (status.is5xxServerError()) {
            return "服务器内部错误";
        }
        return switch (status) {
            case UNAUTHORIZED -> "authentication required";
            case FORBIDDEN -> "forbidden";
            case NOT_FOUND -> "Not found";
            default -> status.getReasonPhrase();
        };
    }

    /**
     * The actionable fields the frontend shows as an error card rather than a one-line toast
     * (main repo {@code isActionableError}): why it was refused, and what to do next.
     *
     * <p>Client errors only. Empty for anything else, and empty for a 4xx with nothing useful to
     * add — the envelope writer drops absent keys rather than sending empty ones.
     */
    static Map<String, Object> guidanceFor(HttpStatus status) {
        if (!status.is4xxClientError()) {
            return Map.of();
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        switch (status) {
            case UNAUTHORIZED -> {
                extra.put("reason", "请求未携带有效凭据");
                extra.put("nextStep", "请附上有效的 Authorization: Bearer <token> 后重试");
            }
            case FORBIDDEN -> {
                extra.put("reason", "当前身份无权执行此操作");
                extra.put("nextStep", "请联系管理员调整权限，或改用本人拥有的资源");
            }
            case NOT_FOUND -> {
                extra.put("reason", "请求的路径不存在");
                extra.put("nextStep", "请检查请求路径与请求方法");
            }
            default -> {
                // No guidance to give; the status and message stand on their own.
            }
        }
        return extra;
    }
}
