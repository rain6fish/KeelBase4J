// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.runtime.authz.AuthorizationRules;
import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.authz.RuleDeniedException;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.scope.DataScopeRules;
import cn.com.keelbase.runtime.scope.Departments;
import cn.com.keelbase.runtime.scope.ScopeDescriptor;
import cn.com.keelbase.runtime.scope.ScopeFilter;
import cn.com.keelbase.runtime.scope.ScopeLevel;
import cn.com.keelbase.runtime.scope.ScopedRow;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A level source may answer from the caller, not only from their role — the range half of the rule
 * source's caller-keyed seam.
 *
 * <p>The two halves have to agree, because they answer one question between them: the rule source says
 * whether a grant carries an ownership condition, and the level source says which rows the row gate
 * then accepts. When the level is chosen from a role string, the contract's two-value vocabulary has
 * already collapsed the deployment's roles, so the row gate answers with the tier-A default rather than
 * with the caller's own data scope — two answers to one question.
 *
 * <p>两个断言是**有意**选的、旧的只带角色的键产不出的那种：在旧键下，「同一个角色的两个人得到不同范围」
 * 根本表达不出来，故它们不可能靠巧合通过。第三个断言反过来钉住**向后兼容**：只覆写旧签名的源**照样生效**
 * ——那条性质是「生成物是用户拥有的产物」的直接后果，旧的应用必须继续编译、继续按它写的那个规则工作。
 *
 * <p>档位来源可以**按调用者**作答，而不只按角色——即规则源那条带调用者的缝在**范围**那一轴上的对应物。
 *
 * <p>两轴必须一致，因为它们合起来回答同一个问题：规则源说一次授权**带不带**属主条件，档位来源说行闸随后
 * **收哪些行**。当档位是由一个角色字符串选出来的时候，契约那个只有两个值的词表已经先把部署的角色塌缩过了，
 * 于是行闸会拿**档 A 的默认**作答、而不是调用方自己的数据范围——同一个问题两个答案。
 */
class PerCallerLevelsTest {

    /** A source that answers from the caller. One caller ranges over every row; the other owns theirs. */
    static class PerCaller extends DataScopeRules {

        private static final String UNRESTRICTED_USER = "7";

        PerCaller() {
            super(Map.of());
        }

        @Override
        public ScopeDescriptor forRole(Principal principal) {
            return ScopeDescriptor.of(UNRESTRICTED_USER.equals(principal.userId())
                    ? ScopeLevel.ALL
                    : ScopeLevel.OWN);
        }
    }

    /** A source that keeps the old key — what a generated application already carries. */
    static class RoleKeyed extends DataScopeRules {

        RoleKeyed() {
            super(Map.of());
        }

        @Override
        public ScopeDescriptor forRole(String role) {
            return ScopeDescriptor.of(ScopeLevel.ALL);
        }
    }

    @Test
    void twoCallersHoldingTheSameRoleGetTheirOwnRanges() {
        ScopeFilter scopes = new ScopeFilter(new PerCaller(), new Departments());
        Principal unrestricted = new Principal("7", PermissionCapabilityList.ROLE_USER);
        Principal ownScoped = new Principal("9", PermissionCapabilityList.ROLE_USER);

        assertEquals(ScopeLevel.ALL, scopes.levelFor(unrestricted).level(),
                "this caller's own declaration is 'every row'; only a caller-carrying key can say it");
        assertEquals(ScopeLevel.OWN, scopes.levelFor(ownScoped).level(),
                "same role, different person, different range — the role-only key cannot express this");
    }

    /**
     * The row gate is where the two halves meet, so the assertion is made through it rather than against
     * the lookup: a foreign row reaches the unrestricted caller and is refused to the own-scoped one.
     * Under the role-only key both would have ranged at the same level, so this cannot pass by accident.
     */
    @Test
    void theRowGateAsksThroughTheCallerKeyedMethod() {
        OwnershipGuard guard = new OwnershipGuard(
                new PermissionAuthorizer(new AuthorizationRules()),
                new ScopeFilter(new PerCaller(), new Departments()));
        ScopedRow someoneElsesRow = rowOwnedBy("alice");

        Principal unrestricted = new Principal("7", PermissionCapabilityList.ROLE_USER);
        assertDoesNotThrow(
                () -> guard.requireAccess(unrestricted, someoneElsesRow, "Customer", "read"),
                "this caller ranges over every row, so another person's row is inside their range");

        Principal ownScoped = new Principal("9", PermissionCapabilityList.ROLE_USER);
        assertThrows(RuleDeniedException.class,
                () -> guard.requireAccess(ownScoped, someoneElsesRow, "Customer", "read"),
                "the same role, the same row, and the row gate must now refuse it");
    }

    /**
     * Backward compatibility, asserted rather than assumed: an application generated before this
     * overload existed overrides the role-keyed method only, and that override must still be the answer
     * the row gate receives.
     */
    @Test
    void aSourceThatKeepsTheRoleKeyIsStillReached() {
        ScopeFilter scopes = new ScopeFilter(new RoleKeyed(), new Departments());
        assertEquals(ScopeLevel.ALL,
                scopes.levelFor(new Principal("1", PermissionCapabilityList.ROLE_USER)).level(),
                "the old signature's override is still what the filtering above it asks");
    }

    private static ScopedRow rowOwnedBy(String owner) {
        return new ScopedRow() {
            @Override
            public String ownerUserId() {
                return owner;
            }

            @Override
            public Long orgId() {
                return null;
            }

            @Override
            public Long deptId() {
                return null;
            }
        };
    }
}
