// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.autoconfigure;

import cn.com.keelbase.runtime.identity.IdentityResolver;
import cn.com.keelbase.runtime.identity.LocalIdentities;
import cn.com.keelbase.runtime.identity.SecurityIdentityResolver;
import cn.com.keelbase.runtime.scope.Departments;
import cn.com.keelbase.runtime.security.CallerAuthenticator;
import cn.com.keelbase.runtime.security.DelegationTokenAuthenticator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The three identity-shaped defaults a deployment replaces when it embeds this runtime (ADR-0017 D6,
 * JV-35): how a request is authenticated, who that person is here, and the department tree that answer
 * is read from.
 *
 * <p>Both answers this runtime needs about identity come from the deployment: <em>how is this request
 * authenticated</em> ({@link CallerAuthenticator}) and <em>who is that person here, with what role and
 * what organization</em> ({@link IdentityResolver}). Standalone, the runtime answers both itself — a
 * delegation token, and a directory declared in {@code LocalIdentities}. Embedded, the host already
 * knows both, and asking the host's user to also hold a KeelBase token, or to be described in a
 * KeelBase directory, would be a second identity system beside the host's. That is the arrangement the
 * "absolutely never three permission systems" warning is about.
 *
 * <p>So they are beans with defaults rather than components with opinions. All three carry
 * {@code @ConditionalOnMissingBean}: a deployment that declares its own gets it, and nothing here runs.
 * That is not a convenience — it is the whole mechanism. Component scanning cannot express it (a
 * {@code @ConditionalOnMissingBean} on a scanned class is not evaluated the way it is on an
 * auto-configuration), so a default that must be replaceable has to be registered here.
 *
 * <p>Two consequences worth knowing. A deployment that replaces {@code CallerAuthenticator} never has
 * {@code keelbase.delegation.secret} resolved, so an embedded host is not made to hold a KeelBase
 * secret it has no use for. And a deployment that replaces {@code IdentityResolver} leaves the tier-A
 * directory ({@code LocalIdentities}) in the context unused: it is a plain bean nobody asks for, which
 * is preferable to making the default unreplaceable to keep it tidy.
 *
 * <p>部署嵌入本运行时时，**要替换掉的那三个身份形状的默认值**（ADR-0017 D6，JV-35）：这条请求怎么被认证、
 * 那个人在这里是谁、以及**那个答案所读的部门树**。
 *
 * <p>本运行时需要的两个身份答案都来自部署方：**这条请求是怎么被认证的**（`CallerAuthenticator`），以及
 * **那个人在这里是谁、什么角色、哪个组织**（`IdentityResolver`）。独立部署时两个都由运行时自己回答——
 * 一个委托令牌，加上 `LocalIdentities` 里声明的一份目录。嵌入时宿主两个都知道，而让宿主的人再持有一个
 * KeelBase 令牌、或在 KeelBase 目录里再被描述一遍，就是**在宿主之外又立一套身份系统**——正是「绝对不要
 * 出现三套权限系统」那句警告所指。
 *
 * <p>所以它们是**带默认实现的 bean**，不是有主见的组件。**三个都带** `@ConditionalOnMissingBean`：声明了自己
 * 实现的部署就用自己那个，这里一个都不注册。这不是便利，**而是机制本身**。组件扫描表达不了它（`@ConditionalOnMissingBean`
 * 标在被扫描的类上，求值方式与在自动配置上不同），所以「必须可被取代的默认值」只能注册在这里。
 *
 * <p>两处后果值得知道。替换掉 `CallerAuthenticator` 的部署，`keelbase.delegation.secret` **根本不会被解析**，
 * 所以嵌入的宿主**不必**持有一个它用不上的 KeelBase 秘密。而替换掉 `IdentityResolver` 的部署，会把 tier-A
 * 目录（`LocalIdentities`）留成上下文里一个没人注入的闲置 bean——这比「为了不留闲置 bean 而让默认实现换不掉」
 * 可取得多。
 */
@AutoConfiguration
public class KeelBaseIdentityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CallerAuthenticator.class)
    CallerAuthenticator delegationTokenAuthenticator(
            @Value("${keelbase.delegation.secret}") String secret,
            @Value("${keelbase.delegation.audience}") String audience) {
        return new DelegationTokenAuthenticator(secret, audience);
    }

    @Bean
    @ConditionalOnMissingBean(IdentityResolver.class)
    IdentityResolver securityIdentityResolver(LocalIdentities directory, Departments departments) {
        return new SecurityIdentityResolver(directory, departments);
    }

    /**
     * The runtime's own department tree, as a default a deployment can replace — the third
     * identity-shaped default, and the one a row range reads when it has to name a department.
     *
     * <p>It is registered here for exactly the reason the class comment gives, and it had to be moved
     * here for the same reason the other two are here: while the class was a scanned {@code @Component},
     * the promise in its own javadoc — that a directory-backed tier B replaces it — could not be kept,
     * because a deployment declaring its own tree got two beans of the type and a {@link ScopeFilter}
     * that matched neither.
     *
     * <p>运行时自己那份部门树，作为**可被部署方替换**的默认——第三个身份形状的默认值，也是行范围要指名一个
     * 部门时读的那一份。
     *
     * <p>它注册在这里，理由与类注释给的完全相同；而它**不得不**搬到这里，理由也与另外两个相同：在那个类
     * 还是被扫描的 `@Component` 时，它自己 javadoc 里的承诺——**目录驱动的档 B 替换它**——是**兑现不了**的，
     * 因为自带一棵树的部署会得到两个同类型的 bean、以及一个两者都不配的 {@link ScopeFilter}。
     */
    @Bean
    @ConditionalOnMissingBean(Departments.class)
    Departments keelBaseDepartments() {
        return new Departments();
    }
}
