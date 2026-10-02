// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.autoconfigure;

import cn.com.keelbase.runtime.authz.AuthorizationRules;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The runtime's own rule source, as a default a deployment can replace — tier A of the two
 * {@link AuthorizationRules} documents.
 *
 * <p>A conditional bean rather than a {@code @Component}, and that is the mechanism, not a preference:
 * a scanned component cannot be replaced, so a host declaring its own rule source gets two beans of
 * this type, and the decision function's constructor parameter matches neither name — Spring raises
 * rather than choosing. Measured, not assumed: with the default still scanned, a host doing exactly
 * that failed to start with {@code NoUniqueBeanDefinitionException}.
 *
 * <p><b>Its own auto-configuration rather than a bean on {@link KeelBaseRuntimeAutoConfiguration}</b>,
 * and the reason is worth knowing: an application whose component scan already covers the core — this
 * repository's own runtime — <em>excludes</em> that auto-configuration, because assembling the same
 * beans twice leaves one repository interface with two definitions and the transactional proxy becomes
 * the one nobody intended (ADR-0017 D3). A default both shapes need therefore cannot live there, or the
 * runtime loses it; the identity seams sit in their own auto-configuration for the same reason.
 *
 * <p>运行时自己那份规则源，作为**可被部署方替换**的默认——即 {@link AuthorizationRules} 记着的两档里的档 A。
 *
 * <p>写成条件 bean 而不是 `@Component`，这是**机制**而非偏好：被扫描的组件**换不掉**，于是自带规则源的
 * 宿主会得到两个同类型的 bean，而判决函数的构造参数名与两个都不配 —— Spring **抛**而不是挑。这是**实测**：
 * 默认仍是被扫描组件时，正是那样做的宿主以 `NoUniqueBeanDefinitionException` 起不来。
 *
 * <p>**自成一条自动配置、而不是挂在 {@link KeelBaseRuntimeAutoConfiguration} 上**，理由值得知道：组件扫描
 * **已经覆盖 core** 的应用——本仓自己的运行时——**排除**了那条自动配置，因为同一批 bean 装配两次会让一个
 * 仓储接口留下两个定义、事务代理变成没人想要的那一个（ADR-0017 D3）。两个形状都要用的默认值**不能待在那里**，
 * 否则运行时就丢了它；身份那两条缝自成一条自动配置，也是同一个理由。
 */
@AutoConfiguration
public class KeelBaseAuthorizationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuthorizationRules.class)
    AuthorizationRules keelBaseAuthorizationRules() {
        return new AuthorizationRules();
    }
}
