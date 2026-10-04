// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.scope.DataScopeRules;
import cn.com.keelbase.runtime.scope.ScopeFilter;
import cn.com.keelbase.runtime.scope.ScopeLevel;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A deployment that brings its own level source keeps it — the same property the rule source's seam
 * test asserts, on the range axis.
 *
 * <p>{@link DataScopeRules} promises that a table-backed source replaces it, and until now that promise
 * could not be kept: the class was a scanned {@code @Component}, so a deployment declaring its own got
 * two beans of the type and a {@link ScopeFilter} whose constructor parameter matched neither name —
 * Spring refuses rather than choosing. Declaring the source here is therefore the whole test, and the
 * assertion keeps it from passing for the wrong reason: tier A declares {@code ALL} for an
 * administrator, so a range of exactly {@code OWN_DEPT} for one is something only the declaration can
 * produce.
 *
 * <p>本测试 = 「自带档位来源的部署方保留自己的那一份」——与规则源那条缝测试断言的是**同一条性质**，
 * 只是落在**范围轴**上。
 *
 * <p>{@link DataScopeRules} 承诺表驱动源可以**替换**它，而在此之前这条承诺**兑现不了**：那个类是**被扫描的
 * `@Component`**，于是自带一份的部署会得到两个同类型的 bean、以及一个构造参数名与两者都不配的
 * {@link ScopeFilter}——Spring 拒绝而不是挑一个。所以「在这里声明档位来源」就是本测试的全部；断言负责不让
 * 它**因错的理由**通过：档 A 对管理员声明的是 {@code ALL}，因此「管理员恰好是 {@code OWN_DEPT}」只有声明的
 * 那一份才产得出。
 */
@SpringBootTest(classes = {CoreTestApplication.class, DataScopeRulesSeamTest.DeclaredLevels.class},
        properties = {
                "keelbase.audit.hmac-key=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.secret=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.audience=keelbase4j",
                "spring.jpa.hibernate.ddl-auto=validate"
        })
class DataScopeRulesSeamTest {

    /** A deployment's own level source: one level that tier A does not declare for that role. */
    @TestConfiguration
    static class DeclaredLevels {

        @Bean
        DataScopeRules declaredLevels() {
            return new DataScopeRules(Map.of(
                    PermissionCapabilityList.ROLE_ADMIN, ScopeLevel.OWN_DEPT));
        }
    }

    @Autowired
    ScopeFilter scopes;

    @Test
    void theDeclaredSourceIsTheOneInEffect() {
        ScopeLevel level = scopes.levelFor(new Principal("root", PermissionCapabilityList.ROLE_ADMIN)).level();

        assertEquals(ScopeLevel.OWN_DEPT, level,
                "the level comes from the declaration, not from tier A — which declares ALL for admin");
    }
}
