// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.scope;

import cn.com.keelbase.runtime.identity.Principal;
import java.util.Map;

/**
 * Which row range a role ranges at — the declared level source, delivery tier A.
 *
 * <p>The reference keeps these levels per role in a table ({@code roles.data_scope}, overridable per
 * subject). The spike declares them instead, which is the same decision made one tier earlier; a tier
 * B deployment replaces this bean and the levels become configurable without the filtering above it
 * changing a line.
 *
 * <p><b>Not a {@code @Component} on purpose</b>, for the reason the rule source documents at length:
 * a scanned component cannot be replaced, so a deployment that declares a table-backed source of this
 * type would get two beans and a {@link ScopeFilter} whose constructor parameter matches neither name.
 * The rule source was arranged this way first and the arrangement was measured, not assumed
 * ({@code NoUniqueBeanDefinitionException}); this one was scanned until a host tried to replace it.
 *
 * <p>某一行的范围按什么取——声明的档位来源，交付档 A。
 *
 * <p>参照实现把这些档位按角色存在一张表里（{@code roles.data_scope}，可按 subject 覆盖）。本实现改为
 * **声明**它们，是同一个决定早做了一档；档 B 的部署**替换这个 bean**，档位就变得可配置，而上层的过滤一行不动。
 *
 * <p>**刻意不是 `@Component`**，理由与规则源那一处写的一样长：**被扫描的组件换不掉**，于是声明了表驱动源的
 * 部署会得到两个同类型的 bean、以及一个构造参数名与两者都不配的 {@link ScopeFilter}。规则源先这么安排，
 * 而那次安排是**实测**出来的（`NoUniqueBeanDefinitionException`）；这一份则一直是被扫描的，直到有宿主想换掉它。
 */
public class DataScopeRules {

    private final Map<String, ScopeLevel> byRole;

    /** A level source assembled by an alternative tier (B: table-backed) without changing anything else. */
    public DataScopeRules(Map<String, ScopeLevel> byRole) {
        this.byRole = Map.copyOf(byRole);
    }

    /** The tier-A levels this runtime declares. */
    public DataScopeRules() {
        this(Map.of(
                Principal.ROLE_USER, ScopeLevel.OWN,
                Principal.ROLE_ADMIN, ScopeLevel.ALL));
    }

    /**
     * The range a role gets. A role with no configured level gets {@link ScopeLevel#OWN} — the
     * tighter answer, never the wider one. A missing configuration must never be the reason someone
     * suddenly sees more.
     */
    public ScopeDescriptor forRole(String role) {
        return ScopeDescriptor.of(byRole.getOrDefault(role, ScopeLevel.OWN));
    }

    /**
     * The range this caller gets. The tier-A source is keyed by role alone, so this is where its
     * levels come from; a source that needs to know <em>who</em> is asking overrides this one, and
     * {@link ScopeFilter} asks through it.
     *
     * <p>It exists for the same measured reason the rule source's caller-keyed method does. The
     * contract's role vocabulary holds two values, and a deployment whose roles are its own collapses
     * several of them onto those two before any level is looked up — so a source answering from its own
     * tables cannot tell two people holding the same role apart, and the row gate would answer with the
     * tier-A default instead of the caller's own data scope. That is two answers to one question, and
     * the row gate is the place they have to agree.
     *
     * <p>按调用方取范围。档 A 的源只按角色取，故这就是它档位的来处；需要知道**谁**在问的源改为覆写这一个，
     * 而 {@link ScopeFilter} 是经那一个来问的。
     *
     * <p>它之所以存在，与规则源那条带调用者的方法同源、同为实测：契约的角色词表只有两个值，而一个角色体系
     * 自成一家的部署，会在任何档位被查**之前**把它的若干角色塌缩到那两个上——于是**读自己表的源分不清同角色的
     * 两个人**，而行闸就会拿**档 A 的默认**作答、而不是调用方自己的数据范围。那是同一个问题有两个答案，
     * 而行闸正是两者必须一致的地方。
     */
    public ScopeDescriptor forRole(Principal principal) {
        return forRole(principal.role());
    }
}
