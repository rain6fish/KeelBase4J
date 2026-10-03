// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A host that renders its own container errors cannot be assembled beside this core: the context refuses
 * to start (the seam record's "F1 附带发现", measured).
 *
 * <p>The prediction on file was that this runtime's error controller would <em>displace</em> a host's
 * — and for a host with none, which is what RuoYi-Vue is, that is right: Boot's own carries
 * {@code @ConditionalOnMissingBean(ErrorController.class)} and stands down, so 404s and container
 * failures arrive in the wire contract. {@code CoreAssemblyTest} asserts that half. This is the other
 * half, and it is not displacement: a host that already answers {@code /error} ends up with two
 * handlers for one path, and Spring refuses the ambiguous mapping while building the context. The
 * application does not start.
 *
 * <p>That puts it in the same class as the two-filter-chain seam (F1): embedding fails loudly at
 * startup rather than quietly answering for the other side. It is also why the polarity of this test is
 * inverted — a green run means the context could not be built, and a context that comes up is the
 * failure.
 *
 * 宿主若**自己渲染**容器错误，就不能与本核心装在一起：上下文**拒绝启动**（缝记录那条「F1 附带发现」的实测）。
 *
 * <p>记录上原本的预测是「本运行时的错误控制器会**顶掉**宿主那个」——对**没有**错误控制器的宿主（RuoYi-Vue 就是）
 * 这句话是对的：Boot 自己那个带 `@ConditionalOnMissingBean(ErrorController.class)`、会让位，故 404 与容器错误按 wire
 * 契约到达；那一半由 `CoreAssemblyTest` 断言。这里是**另一半，而它不是「顶掉」**：已经会答 `/error` 的宿主会得到**两个**
 * 处理同一个路径的方法，Spring 在构建上下文时拒绝这个**歧义映射**——**应用起不来**。
 *
 * <p>于是它与「两条过滤链」那道缝（F1）同类：嵌入以**启动期响亮失败**的方式暴露，而不是悄悄替对方作答。这也是本条测试
 * **极性相反**的原因——绿表示上下文没建起来，而「起来了」才是失败。
 */
class HostErrorControllerClashTest {

    @TestConfiguration
    static class HostRendersItsOwnErrors {

        @Bean
        HostErrorController hostErrorController() {
            return new HostErrorController();
        }
    }

    /** A host's own way of answering container failures — the thing whose absence the prediction assumed. */
    @RestController
    static class HostErrorController implements ErrorController {

        @RequestMapping("/error")
        Map<String, Object> error(HttpServletRequest request) {
            return Map.of("host", "this host renders its own errors");
        }
    }

    @Test
    void aHostThatRendersItsOwnErrorsCannotBeAssembledBesideThisCore() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(
                CoreTestApplication.class, HostRendersItsOwnErrors.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0",
                        "keelbase.audit.hmac-key=0011223344556677889900112233445566778899001122334455667788990011",
                        "keelbase.delegation.secret=0011223344556677889900112233445566778899001122334455667788990011",
                        "keelbase.delegation.audience=keelbase4j");

        RuntimeException refused = assertThrows(RuntimeException.class, app::run,
                "the context came up with two handlers for /error, which Spring is supposed to refuse");
        Throwable cause = rootCause(refused);
        assertTrue(cause.getMessage() != null && cause.getMessage().contains("Ambiguous mapping"),
                "and it is refused as an ambiguous mapping, not for some other reason: " + cause);
    }

    private static Throwable rootCause(Throwable thrown) {
        Throwable current = thrown;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
