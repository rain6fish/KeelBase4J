// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.runtime.authz.AuthorizationRules;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A deployment that brings its own rules keeps them — the seam tier B is built on.
 *
 * <p>{@link AuthorizationRules} documents two tiers and ships tier A. Tier B is a host reading its
 * own role and permission tables, and it only exists if a deployment can <em>replace</em> the default
 * — which is a property of how the default is registered, not of the class. While the default was a
 * scanned {@code @Component} this context would not start at all: two beans of this type, and the
 * decision function's constructor parameter matches neither name, so Spring refuses rather than
 * choosing. Declaring the rules here is therefore the whole test, and the assertion is what keeps it
 * from passing for the wrong reason — the runtime's own tier A grants the wildcard subject, so a
 * capability list of exactly {@code [read]} on one named subject is something only the declared
 * source can produce.
 *
 * <p>本测试 = 「自带规则的部署方保留自己的规则」——**档 B 就建在这条缝上**。
 *
 * <p>{@link AuthorizationRules} 记着两档、自带档 A。档 B 是宿主读**它自己的**角色与权限表，而它成立的
 * 前提是部署方**能替换**默认——这是**默认怎么注册**的性质、不是那个类的性质。默认还是被扫描的
 * `@Component` 时，这个上下文**根本起不来**：同类型两个 bean，而判决函数的构造参数名与两个都不配，
 * 于是 Spring 拒绝而不是挑一个。所以「在这里声明规则」就是本测试的全部；而断言负责不让它**因错的理由**
 * 通过——运行时自带的档 A 授的是**通配 subject**，因此「一个具名 subject 上恰好 `[read]`」只有声明的
 * 那个源才产得出。
 */
@SpringBootTest(classes = {CoreTestApplication.class, AuthorizationRulesSeamTest.DeclaredRules.class},
        properties = {
                "keelbase.audit.hmac-key=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.secret=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.audience=keelbase4j",
                "spring.jpa.hibernate.ddl-auto=validate"
        })
class AuthorizationRulesSeamTest {

    /** A deployment's own rule source: one named subject, one action. Nothing the default declares. */
    @TestConfiguration
    static class DeclaredRules {

        @Bean
        AuthorizationRules declaredRules() {
            return new AuthorizationRules(Map.of(
                    PermissionCapabilityList.ROLE_ADMIN,
                    List.of(new AuthorizationRules.Rule("Customer", "read", null))));
        }
    }

    @Autowired
    PermissionAuthorizer authorizer;

    @Test
    void theDeclaredSourceIsTheOneInEffect() {
        PermissionCapabilityList described =
                authorizer.describe(new Principal("root", PermissionCapabilityList.ROLE_ADMIN));

        PermissionCapabilityList.Resource customer = described.resources().stream()
                .filter(resource -> "Customer".equals(resource.subject()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the declared rules name a Customer subject; described: " + described.resources()));

        assertEquals(List.of("read"), customer.actions(),
                "the actions come from the declaration, not from the runtime's tier A — which would"
                        + " expand `manage` into all four");
    }
}
