// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.coretest.host.HostShapedController;
import cn.com.keelbase.runtime.web.ApiResponseAdvice;
import cn.com.keelbase.runtime.web.ChatController;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;

/**
 * The envelope's scope: a deployment can name packages it answers bare, and nothing else changes.
 *
 * <p>Measured on the RuoYi host, and the reason the property exists: its UI unwraps nothing, so a
 * wrapped {@code /getInfo} left the router reading no roles and the application logged itself out. The
 * two halves asserted here are the ones that make the fix safe — the declared package is left bare, and
 * an undeclared deployment still wraps everything, which is what generated applications and the
 * runtime-neutral frontend rely on.
 *
 * 信封的**作用域**：部署方可以点名它要**原样作答**的包，而其余一切不变。
 *
 * <p>在 RuoYi 宿主上实测，也正是这条属性存在的原因：它的 UI 什么都不解包，于是被包的 `/getInfo` 让路由读不到角色、
 * 应用**自己登出**。这里断言的两半，正是让这个修法**安全**的两半——被点名的包原样作答；而**没有声明**的部署照旧**全包**，
 * 那正是生成物应用与运行时中立前端所依赖的。
 */
@SpringBootTest(classes = CoreTestApplication.class,
        properties = {
                "keelbase.audit.hmac-key=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.secret=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.audience=keelbase4j",
                "spring.jpa.hibernate.ddl-auto=validate",
                "keelbase.wire.exclude-packages=cn.com.keelbase.coretest.host"
        })
class WireEnvelopeExclusionTest {

    @Autowired
    ApiResponseAdvice advice;

    @Test
    void aDeclaredPackageIsAnsweredBareWhileTheRuntimesOwnIsStillWrapped() {
        assertFalse(advice.supports(returnOf(HostShapedController.class), null),
                "a controller in a declared package is not wrapped");
        assertTrue(advice.supports(returnOf(ChatController.class), null),
                "the runtime's own controllers are still wrapped");
    }

    @Test
    void withoutThePropertyEveryControllerIsWrapped() {
        // Built directly rather than booted a second time: the default is the empty list, and the
        // assertion is about the default, not about assembly.
        ApiResponseAdvice undeclared = new ApiResponseAdvice("");
        assertTrue(undeclared.supports(returnOf(HostShapedController.class), null),
                "an undeclared deployment wraps everything, as it did before the property existed");
    }

    private static MethodParameter returnOf(Class<?> controller) {
        Method method = controller.getDeclaredMethods()[0];
        return new MethodParameter(method, -1);
    }
}
