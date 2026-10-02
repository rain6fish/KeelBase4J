// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.runtime.authz.AuthorizationRules;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every rule that grants a subject counts, not only the first one seen.
 *
 * <p>A source states permissions the way its own tables do, and a table-backed source states one rule
 * per permission: {@code system:customer:list} becomes {@code Customer: read}, {@code
 * system:customer:add} becomes {@code Customer: create}. Reading only the first of those made the
 * decision refuse an action the caller held — measured on the RuoYi host, where the refusal arrived as
 * the frozen {@code REASON_DENIED_USER} even though every one of the caller's permission rows was
 * present. Tier A never showed it, because it states one {@code manage} rule per subject and
 * {@code manage} expands to the whole action set.
 *
 * <p>The second assertion is the property that was violated: what {@code decide} allows on a subject
 * must be what {@code describe} says the caller may do on it.
 *
 * <p>授予某个 subject 的**每一条**规则都算数，不是只看见到的第一条。
 *
 * <p>规则源按它自己的表来陈述权限，而表后端的源是**一条权限一条规则**：{@code system:customer:list}
 * 成为 {@code Customer: read}、{@code system:customer:add} 成为 {@code Customer: create}。只读其中第一条，
 * 会让判决拒掉调用者**本已持有**的动作——在若依宿主上实测过：调用者的权限行**一条不缺**，拒绝却仍以冻结的
 * {@code REASON_DENIED_USER} 到达。档 A 从不显露这一点，因为它每个 subject 只陈述**一条** {@code manage}，
 * 而 {@code manage} 展开成整个动作集。
 *
 * <p>第二条断言就是**被违反的那个性质**：{@code decide} 在某 subject 上允许的，必须正是
 * {@code describe} 说该调用者能对它做的。
 */
class MultiRuleSubjectTest {

    /** One rule per permission, the way a table-backed source states them. */
    static class OneRulePerPermission extends AuthorizationRules {

        OneRulePerPermission() {
            super(Map.of());
        }

        @Override
        public List<Rule> rulesFor(Principal principal) {
            return List.of(
                    new Rule("Customer", "read", "ownerUserId"),
                    new Rule("Customer", "create", null));
        }
    }

    @Test
    void anActionGrantedByALaterRuleIsStillAllowed() {
        PermissionAuthorizer authorizer = new PermissionAuthorizer(new OneRulePerPermission());
        Principal caller = new Principal("7", PermissionCapabilityList.ROLE_USER);

        assertTrue(authorizer.decide(caller, "create", "Customer").allowed(),
                "'create' is granted by the second rule; reading only the first refuses it");
        assertTrue(authorizer.decide(caller, "read", "Customer").allowed(),
                "and 'read' is granted by the first");
    }

    @Test
    void theDecisionAndTheDescriptionAgreeOnOneSubject() {
        PermissionAuthorizer authorizer = new PermissionAuthorizer(new OneRulePerPermission());
        Principal caller = new Principal("7", PermissionCapabilityList.ROLE_USER);

        List<String> described = authorizer.describe(caller).resources().stream()
                .filter(resource -> "Customer".equals(resource.subject()))
                .findFirst()
                .orElseThrow()
                .actions();

        assertEquals(described, authorizer.capabilityFor(caller, "Customer").actions(),
                "the capability the decision reads must be the capability it describes");
    }
}
