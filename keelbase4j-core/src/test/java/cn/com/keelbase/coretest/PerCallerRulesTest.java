// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.runtime.authz.AuthorizationRules;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A rule source may answer from the caller, not only from their role.
 *
 * <p>This is what lets a host read its own permission tables through this seam. The host's role keys
 * are not the contract's role vocabulary, so several of its roles collapse onto {@code user} before any
 * rule is looked up — and a source keyed by role alone cannot then tell two people holding the same
 * role apart. Asking the host's directory for "this caller's permissions" is the only way, and it needs
 * the caller to arrive here, which is what the key is for.
 *
 * <p>The assertion is deliberately one that the old key could not produce: under a role-only key, a
 * source that answered differently for two principals of the same role was not expressible at all, so
 * this cannot pass by accident. The failure mode it replaces is worse than a missing rule — reaching for
 * the caller from the security context instead made the decision depend on which path it ran on, and
 * that was measured on a host as twenty-six, twenty-five, twenty-five.
 *
 * <p>规则源可以**按调用者**作答，而不只按角色。
 *
 * <p>这正是宿主得以经由这条缝读它自己的权限表的原因。宿主的角色键不是契约的角色词表，故它的若干角色会在
 * 任何规则被查之前塌缩到 `user` —— 而只按角色取规则的源**分不清**同角色的两个人。问宿主名录
 * 「**这个**调用者的权限」是唯一的办法，而那要求**调用者**到达这里，这就是键的用途。
 *
 * <p>这条断言是**有意**选的、旧键产不出的那种：在只带角色的键下，「同一个角色的两个主体得到不同规则」这件事
 * **根本表达不出来**，故此测试不可能靠巧合通过。它替掉的失败形态比「少一条规则」更糟——改为从安全上下文去够
 * 调用者，会让判决**取决于它跑在哪条路径上**，而那在一个宿主上实测为 26 / 25 / 25。
 */
class PerCallerRulesTest {

    /** A source that answers from the caller. One caller is unrestricted; the other is own-scoped. */
    static class PerCaller extends AuthorizationRules {

        private static final String UNRESTRICTED_USER = "7";

        PerCaller() {
            super(Map.of());
        }

        @Override
        public List<Rule> rulesFor(Principal principal) {
            String owner = UNRESTRICTED_USER.equals(principal.userId()) ? null : "ownerUserId";
            return List.of(new Rule("Customer", "read", owner));
        }
    }

    @Test
    void twoCallersHoldingTheSameRoleGetTheirOwnRules() {
        PermissionAuthorizer authorizer = new PermissionAuthorizer(new PerCaller());
        Principal unrestricted = new Principal("7", PermissionCapabilityList.ROLE_USER);
        Principal ownScoped = new Principal("9", PermissionCapabilityList.ROLE_USER);

        PermissionCapabilityList.Resource first = authorizer.capabilityFor(unrestricted, "Customer");
        assertNotNull(first, "the source answered for the first caller; the key must carry them here");
        assertEquals(PermissionCapabilityList.SCOPE_ALL, first.scope(),
                "this caller's own rule carries no owner column");

        PermissionCapabilityList.Resource second = authorizer.capabilityFor(ownScoped, "Customer");
        assertNotNull(second, "and for the second caller too");
        assertEquals(PermissionCapabilityList.SCOPE_OWN, second.scope(),
                "same role, different person, different rule — only a caller-carrying key can say this");
    }
}
