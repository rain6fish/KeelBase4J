// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.web.servlet.error.ErrorController;
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
 * controller claims.
 *
 * <p>Server faults stay deliberately vague: the status is honest, the message is not, because a 5xx
 * message is the classic place internal detail leaks out.
 */
@RestController
public class WireErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        HttpStatus status = statusOf(request);
        return ResponseEntity.status(status)
                .body(WireEnvelope.error(status.value(), messageFor(status)));
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

    private String messageFor(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "authentication required";
            case FORBIDDEN -> "forbidden";
            case NOT_FOUND -> "Not found";
            default -> status.is5xxServerError() ? "服务器内部错误" : status.getReasonPhrase();
        };
    }
}
