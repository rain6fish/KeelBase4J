// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Wraps every successful REST response in the frozen {@code api-response} envelope.
 *
 * <p>This is the F4 half of the Full profile, and it is not cosmetic: a runtime-neutral frontend
 * unwraps responses through one adapter that expects {@code data} to be present
 * ({@code Web-Admin-Vue/src/api/envelope.ts}). A runtime answering with a bare body breaks that
 * frontend on its very first hop, which is precisely the state every Java endpoint was in before
 * this class existed.
 *
 * <p>Failures are exempt, and the exemption is decided by <em>who produced the body</em> rather than
 * by inspecting it: {@link WireErrorController} already writes the {@code error-body} shape, and
 * wrapping that would nest one envelope inside another.
 */
@ControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return !WireErrorController.class.equals(returnType.getContainingClass());
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        // A String return is written by StringHttpMessageConverter, which cannot serialize an
        // object — wrapping it would turn a working endpoint into a 500.
        if (body instanceof String) {
            return body;
        }
        return WireEnvelope.success(statusOf(response), body);
    }

    /**
     * The status the response already carries, so {@code code} reports what was actually sent rather
     * than an assumed 200.
     */
    private int statusOf(ServerHttpResponse response) {
        if (response instanceof ServletServerHttpResponse servlet) {
            int current = servlet.getServletResponse().getStatus();
            if (current > 0) {
                return current;
            }
        }
        return 200;
    }
}
