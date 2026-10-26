// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.security.CallerAuthenticationFailure;
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
            case UNAUTHORIZED -> "认证失败";
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
                extra.put("reason", "请求未通过身份认证");
                extra.put("nextStep", "请附上有效的 `Authorization: Bearer <token>` 后重试；令牌由 "
                        + "KeelBase 用部署的 `keelbase.delegation.secret` 签发");
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

    /**
     * The 401 guidance for a refusal the authenticator could explain.
     *
     * <p>The failure names the check that refused; the readings behind it ({@code expectedAudience},
     * {@code actualAudience}, issuer, expiry) travel inside {@code explanation}, which the frozen
     * {@code error-body} allows to be an object. The secret and the token are never part of it.
     *
     * <p>认证器能解释的拒绝，其 401 指引。
     *
     * <p>失败记录点名拒绝的那道检查；背后的读数（`expectedAudience`、`actualAudience`、签发方、过期时刻）
     * 放在 `explanation` 里 —— 冻结的 `error-body` 允许它是一个对象。secret 与令牌从不进入其中。
     */
    static Map<String, Object> authenticationGuidance(CallerAuthenticationFailure failure) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("errorCode", failure.code());
        extra.put("reason", failure.why());
        extra.put("impact", "本次请求未进入治理链，未执行任何工具");
        extra.put("nextStep", failure.nextStep());

        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("stage", "authentication");
        explanation.put("code", failure.code());
        explanation.put("reason", failure.why());
        explanation.putAll(failure.detail());
        extra.put("explanation", explanation);
        return extra;
    }

    /**
     * The 403 guidance for a rule or row refusal, carrying the action and subject that were refused.
     *
     * <p>A caller who is told only "forbidden" cannot tell whether they lack the action on this
     * subject, or whether the row is outside their data range. The guard knows both, so the refusal
     * names them. The row gate still claims no {@code deniedBy}: the frozen set has no basis for it,
     * and inventing one would be this runtime minting contract vocabulary (see
     * {@code OwnershipGuard}).
     *
     * <p>An {@code explanation} is written whenever there is anything to explain — a basis, a subject,
     * or an action. A row denial carries no basis but does name what was refused, so it emits
     * {@code {reason, subject, action}} with {@code deniedBy} absent. That absence is the statement:
     * no basis is claimed. {@code error-body.schema.json} types {@code explanation} as
     * {@code ["object","string"]} with no inner schema, so this shape is schema-valid.
     *
     * <p>规则或行拒绝的 403 指引，带上被拒绝的动作与主体。
     *
     * <p>只被告知「forbidden」的调用方分不清：是自己对这个主体缺这个动作，还是这一行不在自己的数据范围内。
     * 守卫两样都知道，故拒绝点名它们。行闸仍然**不声称** `deniedBy`：冻结的集合里没有它的依据，编一个
     * 就是本运行时在铸造契约词表（见 `OwnershipGuard`）。
     *
     * <p>只要有可解释的东西——依据、主体、动作三者之一——就写 `explanation`。行拒绝没有依据，但确实点名拒了什么，
     * 故交出 `{reason, subject, action}` 而 `deniedBy` 缺席。**缺席本身即是那句话**：不声称依据。
     * `error-body.schema.json` 把 `explanation` 定为 `["object","string"]`、内部无 schema，故该形状 schema 合法。
     */
    static Map<String, Object> ruleDeniedGuidance(String reason, String deniedBy, String subject,
                                                  String action, String nextStep) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("errorCode", deniedBy == null ? "ROW_OUT_OF_SCOPE" : "FORBIDDEN_RULE");
        extra.put("reason", reason);
        extra.put("impact", "本次请求被授权链拒绝，未执行任何工具，也未产生任何写操作");
        Object fallback = guidanceFor(HttpStatus.FORBIDDEN).get("nextStep");
        extra.put("nextStep", nextStep != null ? nextStep : fallback);
        if (deniedBy != null || subject != null || action != null) {
            Map<String, Object> explanation = new LinkedHashMap<>();
            if (deniedBy != null) {
                explanation.put("deniedBy", deniedBy);
            }
            explanation.put("reason", reason);
            if (subject != null) {
                explanation.put("subject", subject);
            }
            if (action != null) {
                explanation.put("action", action);
            }
            extra.put("explanation", explanation);
        }
        return extra;
    }
}
