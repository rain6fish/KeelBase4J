// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.authz.RuleDeniedException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Renders failures the application raises <em>on purpose</em> in the frozen {@code error-body}
 * shape, carrying the fields the caller needs to act on them.
 *
 * <p>Two of those used to arrive with the reason stripped. An authorization refusal knew its own
 * basis ({@code casl}) and then told the caller only "forbidden"; the frontend turns that basis into
 * the "what to do" line it shows the user, so dropping it costs the caller the one piece of
 * information they could act on. The reference sends it as
 * {@code explanation: {deniedBy, reason}} and the Java side now matches that shape exactly rather
 * than approximating it.
 *
 * <p>This is an advice rather than a handler on {@link WireErrorController} because the failures
 * come from other controllers: a handler declared in a controller only sees that controller's
 * exceptions.
 *
 * <p>Server faults are handled here too, but only to keep the envelope: a 5xx is reported with the
 * status and nothing else, so an internal message cannot become a leak. Its text is not repeated.
 *
 * <p><b>Why this advice orders itself first (S9).</b> An embedded host brings an exception handler of
 * its own, and the common shape — RuoYi's included — catches {@code RuntimeException}, a supertype of
 * both exceptions below. Spring resolves between advices by order, so a host advice reached earlier
 * does not answer alongside this one: it <em>takes the refusal</em>, writes its own success envelope,
 * and because that body is not ours to shape, {@link ApiResponseAdvice} wraps it — the caller reads
 * HTTP 200 with the refusal nested inside {@code data}. The refusal is real; the contract is not what
 * arrives. Measured rather than deduced: without an order the outcome turns on registration order, so
 * the same host reads a 403 one day and a 200 the next. Taking the highest precedence makes this
 * runtime's refusal arrive as a refusal wherever it is embedded — the invariant every client written
 * against the frozen envelope depends on.
 *
 * <p><b>本条 advice 为什么把自己排在最前（S9）。</b>被嵌进宿主后，宿主自带异常处理器，而常见形状
 * （含 RuoYi）捕的是 {@code RuntimeException} —— 下面两个异常的超类。Spring 按顺序在 advice 之间裁决，
 * 因此排在前面的宿主 advice 不是与本条并列作答，而是**把拒绝拿走**：它写自己的成功信封，而那个 body 不是
 * 我们塑的、于是被 {@link ApiResponseAdvice} 再包一层 —— 调用方读到 HTTP 200、拒绝嵌在 {@code data} 里。
 * 拒绝是真的，到达客户端的契约不是。这是**实测**而非推断：不声明顺序时结果取决于注册顺序，同一个宿主今天
 * 读到 403、明天读到 200。取最高优先级后，本运行时的拒绝无论被嵌到哪里都以拒绝的形式到达 —— 这正是每一个
 * 按冻结信封写的客户端所依赖的不变量。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class WireExceptionAdvice {

    /**
     * A refusal by the authorization contracts.
     *
     * <p>{@code deniedBy} may be absent — the row gate denies a row the rules allowed, and the frozen
     * {@code permission-decision} defines no basis for that. When it is absent the {@code explanation}
     * is omitted rather than sent empty, so the frontend reads "no basis claimed" instead of a basis
     * it would then act on.
     */
    @ExceptionHandler(RuleDeniedException.class)
    public ResponseEntity<Map<String, Object>> ruleDenied(RuleDeniedException denied) {
        String reason = denied.getReason() != null
                ? denied.getReason()
                : WireFailure.messageFor(HttpStatus.FORBIDDEN);
        Map<String, Object> extra = new LinkedHashMap<>(WireFailure.guidanceFor(HttpStatus.FORBIDDEN));
        extra.put("reason", reason);
        if (denied.deniedBy() != null) {
            extra.put("explanation", Map.of("deniedBy", denied.deniedBy(), "reason", reason));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(WireEnvelope.error(HttpStatus.FORBIDDEN.value(), reason, extra));
    }

    /**
     * Any other deliberate status failure — an ownership check that is not a rule decision, a
     * confirmation belonging to another operator. The status is taken as given, and a client-status
     * reason is passed through because the runtime wrote it to be read.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> refused(ResponseStatusException refused) {
        HttpStatus status = resolved(refused);
        String message = status.is4xxClientError() && refused.getReason() != null
                ? refused.getReason()
                : WireFailure.messageFor(status);
        return ResponseEntity.status(status)
                .body(WireEnvelope.error(status.value(), message, WireFailure.guidanceFor(status)));
    }

    /** A status this runtime did not mean to send is a fault, whatever it claimed to be. */
    private HttpStatus resolved(ResponseStatusException refused) {
        HttpStatus status = HttpStatus.resolve(refused.getStatusCode().value());
        if (status == null || status.is5xxServerError()) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return status;
    }
}
