// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Local debug mode: the deterministic seams, chosen on purpose rather than as a fallback.
 *
 * <p>Without a provider the runtime already routes by rules, so the loop can be demonstrated with no
 * model. The missing case is the opposite one: a deployment that <em>carries</em> a provider cannot
 * start without a key, because the provider's own auto-configuration insists on one. This
 * configuration makes "offline" an explicit, default-off switch for that deployment — the environment
 * post-processor beside it turns the provider's auto-configuration off, and these beans take the two
 * seams.
 *
 * <p>The beans are the existing defaults, not a second implementation: {@link RuleBasedPlanner} and
 * {@link LocalDebugReplier} (which delegates every real outcome to {@link DeterministicReplier}). The
 * ordering is what lets them win without a {@code @Primary}: this configuration runs before the
 * runtime's own defaults and before the Spring AI adapter's, so both see a bean already present and
 * stand down — the arrangement JV-14 established and the adapter relies on.
 *
 * <p>Off by default, and it stays a deployment decision: nothing here activates unless
 * {@code keelbase.local-debug.enabled=true}.
 *
 * <p>本地调试模式：**有意选择**确定性接缝，而不是把它当回落。
 *
 * <p>没有 provider 时，运行时本来就按规则路由，故整条闭环可以不带模型演示。缺的是相反的情形：一个**带着**
 * provider 的部署，没有 key 就起不来，因为 provider 自己的自动配置坚持要一个。本配置把「离线」做成那个部署
 * **显式的、默认关**的开关 —— 旁边的环境后处理器关掉 provider 自动配置，这两个 bean 接管两条接缝。
 *
 * <p>它们是**既有**默认实现，不是第二套：{@link RuleBasedPlanner} 与 {@link LocalDebugReplier}（真实
 * outcome 全部委托给 {@link DeterministicReplier}）。顺序让它们无需 {@code @Primary} 就赢：本配置先于
 * 运行时自己的默认、也先于 Spring AI 适配器，于是两者都看见已有 bean 并让位 —— 这正是 JV-14 立下、适配器
 * 依赖的那个安排。
 *
 * <p>默认关，且仍是部署方的决定：除非 {@code keelbase.local-debug.enabled=true}，这里什么都不生效。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "keelbase.local-debug", name = "enabled", havingValue = "true")
@AutoConfigureBefore(
        value = {RuleBasedPlannerAutoConfiguration.class, ChatReplierAutoConfiguration.class},
        name = {
            "cn.com.keelbase.springai.SpringAiPlannerAutoConfiguration",
            "cn.com.keelbase.springai.SpringAiChatReplierAutoConfiguration"
        })
public class LocalDebugAutoConfiguration {

    private static final Log log = LogFactory.getLog(LocalDebugAutoConfiguration.class);

    /**
     * Announce the mode once the logging system is up. The environment post-processor cannot do this:
     * it runs before logging is initialized, so its line would be dropped.
     *
     * 在日志系统就绪后播报一次。环境后处理器做不到：它早于日志初始化，那一行会被丢掉。
     */
    public LocalDebugAutoConfiguration() {
        log.info("local debug mode is on: no model provider is configured; requests route by rules only");
    }

    @Bean
    @ConditionalOnMissingBean(ToolCallPlanner.class)
    ToolCallPlanner localDebugPlanner() {
        return new RuleBasedPlanner();
    }

    @Bean
    @ConditionalOnMissingBean(ChatReplier.class)
    ChatReplier localDebugReplier() {
        return new LocalDebugReplier();
    }
}
