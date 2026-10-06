// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The multi-step path, registered on its own rather than beside the planner.
 *
 * <p>That separation is deliberate: {@link SpringAiPlannerAutoConfiguration} is conditional on no
 * other {@code ToolCallPlanner} existing, because exactly one planner may be a bean. Hanging the
 * runner off it would mean a host that supplies its own planner silently loses the runner too —
 * a different thing going missing because of an unrelated choice.
 *
 * <p>多步路径**单独注册**，不和规划器挤在一起。
 *
 * <p>这条分离是刻意的：{@link SpringAiPlannerAutoConfiguration} 的条件是**没有别的**
 * {@code ToolCallPlanner}（规划器只能有一个 bean）。把 runner 挂在它下面，会让**自带规划器的宿主连 runner 也
 * 一起丢掉**——一件不相干的选择导致另一件东西消失。
 */
@AutoConfiguration
@AutoConfigureAfter(ChatClientAutoConfiguration.class)
@ConditionalOnBean({ChatClient.Builder.class, GovernedExecutionEngine.class})
@ConditionalOnMissingBean(GovernedTaskRunner.class)
public class GovernedTaskRunnerAutoConfiguration {

    @Bean
    GovernedTaskRunner governedTaskRunner(ChatClient.Builder builder, ToolRegistry tools,
                                          GovernedExecutionEngine engine) {
        return new GovernedTaskRunner(builder.build(), tools, engine::execute);
    }
}
