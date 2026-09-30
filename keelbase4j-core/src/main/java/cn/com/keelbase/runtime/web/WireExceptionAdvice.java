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
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /**
     * A path no handler matches. Its absence from this advice was the wider half of the S9 door:
     * because no handler here claimed the type, in an embedded host the exception fell to the host's
     * generic {@code Exception} handler, which answered HTTP 200 with its own body, and
     * {@link ApiResponseAdvice} — its exemption turns on who produced the body, and that body is not
     * ours — labelled it a success. The caller read "操作成功" for a route that does not exist.
     * Ordering cannot reach this one: with no handler declared here there is no second claimant for
     * the order to rank. Covering the type is what fixes it.
     *
     * <p>{@link WireFailure} already carried 404 wording and guidance — the type was the only part
     * missing, so this is an omission closed rather than a policy chosen.
     *
     * <p><b>没有被处理器接手的路径。</b>它在本条 advice 里的缺席，正是 S9 那扇门更宽的那一半：此处
     * 无人认领该类型，故在被嵌入的宿主里异常落到宿主那个泛型 {@code Exception} 处理器上，后者以
     * HTTP 200 写自己的正文，而 {@link ApiResponseAdvice}（它的豁免取决于正文是谁产的，而那份正文不
     * 是我们的）把它标成成功 —— 调用方为一条不存在的路由读到了「操作成功」。排序到不了这里：此处未声明
     * 处理器，就没有第二个主张者让排序去排；修法是**覆盖该类型**。{@link WireFailure} 本就带着 404 的
     * 文案与指引 —— 缺的只有类型这一环，故这是**补上一处遗漏**，不是选定了某种策略。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noSuchPath(NoResourceFoundException missing) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(WireEnvelope.error(HttpStatus.NOT_FOUND.value(),
                        WireFailure.messageFor(HttpStatus.NOT_FOUND),
                        WireFailure.guidanceFor(HttpStatus.NOT_FOUND)));
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
