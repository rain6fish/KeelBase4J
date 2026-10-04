// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.scope.Departments;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A deployment that brings its own department tree keeps it — the third of the identity-shaped
 * defaults, asserted the same way the other two are.
 *
 * <p>{@link Departments} promises that a directory-backed tree replaces it, and that promise could not
 * be kept while the class was a scanned {@code @Component}: a deployment declaring its own tree got two
 * beans of the type and a {@code ScopeFilter} whose constructor parameter matched neither name. Declaring
 * the tree here is therefore the whole test, and the assertion keeps it from passing for the wrong
 * reason — tier A's tree holds three departments and none of them is 99, so a department that is known
 * can only have come from the declaration.
 *
 * <p>本测试 = 「自带部门树的部署方保留自己的那棵」——三个身份形状的默认值里的第三个，断言方式与另外两个相同。
 *
 * <p>{@link Departments} 承诺**目录驱动的树替换它**，而这个承诺在那个类还是被扫描的 `@Component` 时**兑现不了**：
 * 自带一棵树的部署会得到两个同类型的 bean、以及一个构造参数名与两者都不配的 `ScopeFilter`。所以「在这里声明
 * 这棵树」就是本测试的全部；断言负责不让它**因错的理由**通过——档 A 那棵树只有三个部门、其中没有 99，因此一个
 * **认得出**的部门只可能来自声明。
 */
@SpringBootTest(classes = {CoreTestApplication.class, DepartmentsSeamTest.DeclaredTree.class},
        properties = {
                "keelbase.audit.hmac-key=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.secret=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.audience=keelbase4j",
                "spring.jpa.hibernate.ddl-auto=validate"
        })
class DepartmentsSeamTest {

    /** A deployment's own tree: one department, and one tier A does not have. */
    @TestConfiguration
    static class DeclaredTree {

        @Bean
        Departments declaredDepartments() {
            return new Departments(Map.of(
                    99L, new Departments.Dept(99, "Host — Support", null)));
        }
    }

    @Autowired
    Departments departments;

    @Test
    void theDeclaredTreeIsTheOneInEffect() {
        assertTrue(departments.find(99L).isPresent(),
                "the tree in the context is the declared one — tier A declares 10, 11 and 12 only");

        assertEquals(List.of("Host — Support"), departments.pathNames(99L),
                "and its names come from the declaration too");
    }
}
