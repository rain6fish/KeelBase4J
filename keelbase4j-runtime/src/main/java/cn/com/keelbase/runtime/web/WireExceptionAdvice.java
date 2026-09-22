// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.authz.RuleDeniedException;
import java.util.LinkedHashMap;
import java.util.Map;
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
 */
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
