// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import cn.com.keelbase.runtime.pipeline.ChatReplierAutoConfiguration;
import cn.com.keelbase.runtime.pipeline.RuleBasedPlannerAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Assembles the runtime core for an application that is not this repository's (ADR-0017).
 *
 * <p>Everything the runtime is lives under {@code cn.com.keelbase.runtime}: the trust loop's engine
 * and its stores, the governance surface, the identity seam, and the security filter chain. Until this
 * class existed, the only thing that registered them was {@code @SpringBootApplication} on
 * {@code KeelBase4JApplication} — that class's component scan, whose root is the package they happen to
 * share. A host application scans its own packages and has never heard of ours, so it would start up
 * with the jar on its classpath and none of the beans in its context: endpoints that do not exist, and
 * no error to say why.
 *
 * <p>Two things follow, and both are deliberate:
 *
 * <p><b>Three scans, not one, and that is the part worth remembering.</b> Spring Boot derives all of
 * these from the package the <em>application</em> lives in: the components it finds, the entities its
 * persistence unit knows, and the Spring Data repositories it proxies. A host's package is not ours, so
 * each of the three comes up empty in turn — the third one is the quietest, because an interface with no
 * proxy produces no bean and no complaint until something asks for it. Component-scanning from a library
 * is worth being uneasy about, and this is the case where it is honest: {@code cn.com.keelbase.runtime}
 * belongs to this repository exclusively. It is also what keeps thirty-odd annotated classes from
 * having to be rewritten as bean methods purely to satisfy a packaging convention.
 *
 * <p><b>Scheduling is enabled here, not left to the host.</b> The confirmation offline window is only
 * meaningful with a periodic sweep behind it (see {@code ConfirmationSweeper}); an embedded core that
 * silently lost the sweep would keep confirmations decidable forever while looking installed. A host
 * that schedules its own work already is unaffected — {@code @EnableScheduling} is additive.
 *
 * <p>{@code @ConfigurationPropertiesScan} comes along for the same reason: the runtime's own properties
 * ({@code keelbase.*}) are part of what it is, and a host should not have to know they exist.
 *
 * <p>What is <em>not</em> here is as deliberate: no {@code application.properties}, no
 * {@code @SpringBootApplication}, no server or datasource opinion. Those belong to whoever deploys the
 * application — see ADR-0017 D4/D5.
 *
 * <p><b>The scheduler is here because {@code @EnableScheduling} alone is not enough.</b> Boot's
 * {@code TaskSchedulingAutoConfiguration} supplies a {@code TaskScheduler}, but only once a
 * {@code internalScheduledAnnotationProcessor} bean exists — and this class's own
 * {@code @EnableScheduling} is evaluated too late in the auto-configuration ordering to be what
 * satisfies that. The reference application, {@code KeelBase4JApplication}, declares
 * {@code @EnableScheduling} on the application class itself, early enough to win the race; that is a
 * property of the reference application, not of this core, and a host inherits none of it. Measured:
 * the first host to embed the core failed to start on
 * {@code ChatStreamController} requiring a {@code TaskScheduler} that could not be found. So the core
 * supplies one, with {@code @ConditionalOnMissingBean} — the same arrangement as the identity
 * defaults: this runtime needs it, so it brings a default, and a deployment that has its own keeps
 * it.
 *
 * <p><b>调度器放在这里，是因为光有 {@code @EnableScheduling} 不够。</b> Boot 的
 * {@code TaskSchedulingAutoConfiguration} 会提供 {@code TaskScheduler}，但只在
 * {@code internalScheduledAnnotationProcessor} 这个 bean 存在之后——而本类自己的
 * {@code @EnableScheduling} 在自动配置排序里**评估得太晚**，满足不了那个条件。参照应用
 * {@code KeelBase4JApplication} 把 {@code @EnableScheduling} 声明在应用类上，早得足以赢得这场竞争；
 * 那是**参照应用的**性质，不是本 core 的，宿主一点都继承不到。**实测**：第一个嵌入本 core 的宿主
 * 起不来，报 {@code ChatStreamController} 需要一个找不到的 {@code TaskScheduler}。于是 core 自带一个，
 * 带 {@code @ConditionalOnMissingBean}——与身份默认值同一套安排：本运行时需要它，就带一个默认，
 * 有自己那套的部署方保留自己的。
 */
@AutoConfiguration
// The two single-slot fallbacks are excluded from this scan, and that exclusion is load-bearing.
// They are @AutoConfiguration classes, and @AutoConfiguration is meta-annotated @Configuration, so a
// scan over their package registers them as ordinary configurations — in the user phase, before any
// auto-configuration is ordered. Each then takes its single slot first, and the model-backed
// auto-configurations (which correctly declare @AutoConfigureBefore these two) see the bean and back
// off: measured, with a model configured, neither the planner nor the replier was the model's. Left to
// the imports file they are ordered like every other auto-configuration, and their own
// @ConditionalOnMissingBean does what it says.
//
// 两条**单槽兜底**被排除出这次扫描，而这条排除是承重的。它们是 @AutoConfiguration 类，而
// @AutoConfiguration 的元注解含 @Configuration，所以扫到它们所在包就会把它们当**普通配置**注册——那是在
// **用户阶段**、早于任何自动配置被排序。于是各自**先占住自己那唯一的槽**，而模型驱动的自动配置（它们**正确
// 地**声明了 @AutoConfigureBefore 指向这两条）看见 bean 就让位：实测，配好模型后规划器与回复器**都不是模型的**。
// 交给 imports 文件，它们就和别的自动配置一样参与排序，而它们自己的 @ConditionalOnMissingBean 也就名副其实了。
@ComponentScan(
        value = "cn.com.keelbase.runtime",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {RuleBasedPlannerAutoConfiguration.class, ChatReplierAutoConfiguration.class}))
@EntityScan("cn.com.keelbase.runtime")
@EnableJpaRepositories("cn.com.keelbase.runtime")
@ConfigurationPropertiesScan("cn.com.keelbase.runtime")
@EnableScheduling
public class KeelBaseRuntimeAutoConfiguration {

    /**
     * The scheduler this runtime's streaming endpoints need, supplied here rather than assumed from the
     * hosting application's auto-configuration. A deployment that already has one keeps it.
     */
    @Bean
    @ConditionalOnMissingBean(TaskScheduler.class)
    TaskScheduler keelBaseTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("keelbase-sched-");
        scheduler.setDaemon(true);
        return scheduler;
    }
}
