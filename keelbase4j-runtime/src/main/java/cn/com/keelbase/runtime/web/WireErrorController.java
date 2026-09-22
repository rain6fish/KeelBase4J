// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Renders every container-level failure in the frozen {@code error-body} shape.
 *
 * <p>By default Spring Boot answers these with its own {@code {timestamp, status, error, path}}
 * body, which is not the wire contract — and the frontend reads {@code message} / {@code reason} /
 * {@code impact} / {@code nextStep} off the error body to tell the user what went wrong and what to
 * do about it (NC-2). A 401 therefore has to arrive in the same four-key shape as a success, or the
 * frontend cannot explain it.
 *
 * <p>Declaring an {@link ErrorController} bean makes Boot's own back off, so this is the single place
 * container errors are shaped — including the 401 that
 * {@link cn.com.keelbase.runtime.security.SecurityConfig} raises and the 404 for a path no
 * controller claims. Failures the application raises on purpose are shaped by
 * {@link WireExceptionAdvice} instead; both take their wording from {@link WireFailure}, so a 403
 * reads the same however it was produced.
 */
@RestController
public class WireErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        HttpStatus status = statusOf(request);
        return ResponseEntity.status(status)
                .body(WireEnvelope.error(status.value(),
                        WireFailure.messageFor(status),
                        WireFailure.guidanceFor(status)));
    }

    private HttpStatus statusOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (attribute instanceof Integer code) {
            HttpStatus resolved = HttpStatus.resolve(code);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
