// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.authz;

import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.List;
import java.util.Map;

/**
 * The authorization rule source — who may do what, before any decision is taken.
 *
 * <p>This is delivery **tier A** (main repo {@code docs/authorization-architecture.md} §9): the rules
 * a project declares, not rows a runtime manages. There is deliberately no {@code roles} /
 * {@code permissions} table — tier A gets page/button/row capability without one. Tier B replaces
 * this bean with a table-backed source; the decision function
 * ({@link PermissionAuthorizer}) does not change, because it reads rules only through
 * {@link #rulesFor(String)}.
 *
 * <p>Rule subjects are entity names; {@code ownerField} names the column holding the row's owner,
 * and {@code null} means the grant carries no row-level restriction. A rule on the wildcard subject
 * grants everything, which is how the contract's {@code admin} role is expressed.
 *
 * <p><b>Not a {@code @Component} on purpose.</b> The class is not scanned; the runtime supplies the
 * tier-A instance as a conditional bean instead ({@code KeelBaseAuthorizationAutoConfiguration}), which
 * is what makes tier B possible at all: a host that declares a table-backed source of this type replaces
 * this default, and a scanned component cannot be replaced — the deployment would end up with two
 * sources of the same type and a context that refuses to start. The identity seams are arranged the
 * same way, and accept the same cost: a deployment that replaces this one leaves the default unused.
 *
 * <p>**刻意不是 `@Component`。** 这个类不被扫描；运行时改为以一个**条件 bean** 供给档 A 实例
 * （`KeelBaseAuthorizationAutoConfiguration`），而这正是档 B 得以存在的前提：声明了表驱动源的宿主**替换**掉
 * 这个默认，而被扫描出来的组件**换不掉** —— 部署方会得到两个同类型的源、以及一个**拒绝启动**的上下文。
 * 身份那两条缝是同样的安排，也接受同样的代价：替换掉它的部署，把这个默认留成闲置。
 */
public class AuthorizationRules {

    /** A declared grant: act on {@code subject}, with an ownership condition when {@code ownerField} is set. */
    public record Rule(String subject, String action, String ownerField) {
    }

    private final Map<String, List<Rule>> byRole;

    /** A rule source assembled by an alternative tier (B: table-backed) without changing the decision function. */
    public AuthorizationRules(Map<String, List<Rule>> byRole) {
        this.byRole = Map.copyOf(byRole);
    }

    /** The tier-A rules this runtime declares. */
    public AuthorizationRules() {
        this(Map.of(
                PermissionCapabilityList.ROLE_ADMIN, List.of(
                        new Rule(PermissionCapabilityList.SUBJECT_ALL, PermissionCapabilityList.MANAGE, null)),
                PermissionCapabilityList.ROLE_USER, List.of(
                        new Rule("Customer", PermissionCapabilityList.MANAGE, "ownerUserId"),
                        new Rule("FollowUp", PermissionCapabilityList.MANAGE, "userId"))));
    }

    /**
     * The rules for a role. The tier-A source is keyed by role alone, so this is where its rules come
     * from; a source that needs to know <em>who</em> is asking overrides {@link #rulesFor(Principal)}
     * instead, and the decision function asks through that one.
     *
     * 按角色取规则。档 A 的源只按角色取，故这就是它规则的来处；需要知道**谁**在问的源改为覆写
     * {@link #rulesFor(Principal)}，而判决函数是经那一个来问的。
     */
    public List<Rule> rulesFor(String role) {
        return byRole.getOrDefault(role, List.of());
    }

    /**
     * The rules for a caller.
     *
     * <p>It exists because a role string is not enough to answer with. The contract's role vocabulary
     * holds two values, and a deployment whose roles are its own collapses several of them onto those
     * two before any rule is looked up — so a source that answers from its own tables cannot tell two
     * people holding the same role apart, and asking the caller through the security context instead
     * makes the answer depend on which path the decision runs on. Measured on a host: one build, three
     * runs, twenty-six assertions then twenty-five twice, refusing at execution. The key carries the
     * caller, and a source that ignores it is unchanged.
     *
     * <p>本方法之所以存在，是因为**一个角色字符串不足以作答**。契约的角色词表只有两个值，而一个角色体系
     * 自成一家的部署，会在任何规则被查之前把它的若干角色塌缩到那两个上——于是**读自己表的源分不清同角色的
     * 两个人**；而改从安全上下文去够调用者，会让答案**取决于判决跑在哪条路径上**。在一个宿主上实测：一份
     * 构建、三次运行，26 条断言，随后两次 25 条，且拒在**执行**步。键带上调用者，而不理它的源一行未改。
     */
    public List<Rule> rulesFor(Principal principal) {
        return rulesFor(principal.role());
    }
}
