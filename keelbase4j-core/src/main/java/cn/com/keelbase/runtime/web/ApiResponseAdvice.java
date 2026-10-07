// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
 * by inspecting it: {@link WireErrorController} and {@link WireExceptionAdvice} already write the
 * {@code error-body} shape, and wrapping either would nest one envelope inside another — turning a
 * 403 into a 200 with the refusal buried in {@code data}.
 */
@ControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    /**
     * Package prefixes whose controllers are answered as-is, bare.
     *
     * <p>Empty by default, so the behaviour above is what a deployment gets unless it says otherwise.
     * The property exists because "every successful response" was read one controller too widely: a host
     * that brings its <em>own</em> frontend alongside this runtime has controllers whose consumer is
     * that frontend, not the runtime-neutral one, and wrapping them hands the wrong envelope to the
     * wrong client. Measured on the RuoYi host: its UI unwraps nothing, so a wrapped {@code /getInfo}
     * left its router reading no roles and it logged itself out.
     *
     * <p>A prefix matches the package and everything under it, so {@code com.ruoyi} covers
     * {@code com.ruoyi.system.controller}. Declaring a prefix that this runtime's own controllers live
     * under would turn the envelope off for them too — that is the deployment's call to make, and the
     * reason this is a property rather than a hard-coded list.
     *
     * 包前缀清单，其下的控制器**原样作答、不包信封**。
     *
     * <p>默认**为空**，故除部署另有声明，上面那套行为就是它拿到的。「每一个成功响应」被读宽了一个控制器：
     * 一个把**自己的**前端与运行时并置的宿主，它的控制器消费者是**那个前端**、不是运行时中立那个，把它们包起来
     * 等于把错误的信封交给错误的客户端。**RuoYi 宿主实测**：它的 UI 什么都不解包，于是被包的 `/getInfo` 让它的
     * 路由读不到角色、**自己登出**。
     *
     * <p>前缀匹配该包**及其下全部**，故 `com.ruoyi` 覆盖 `com.ruoyi.system.controller`。若把**本运行时自己的**控制器
     * 所在的前缀也声明进来，信封对它们**也会关掉**——那是部署自己的选择，也正是这里做成属性、而不是写死清单的原因。
     */
    private final List<String> excludedPackages;

    public ApiResponseAdvice(@Value("${keelbase.wire.exclude-packages:}") String excludePackages) {
        this.excludedPackages = Arrays.stream(excludePackages.split(","))
                .map(String::trim)
                .filter((prefix) -> !prefix.isEmpty())
                .toList();
    }

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> writer = returnType.getContainingClass();
        if (WireErrorController.class.equals(writer) || WireExceptionAdvice.class.equals(writer)) {
            return false;
        }
        return !isExcluded(writer);
    }

    /** Whether the controller's package is one this deployment asked to leave bare. */
    private boolean isExcluded(Class<?> writer) {
        String name = writer.getPackageName();
        for (String prefix : excludedPackages) {
            if (name.equals(prefix) || name.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
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
