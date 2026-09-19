// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import cn.com.keelbase.runtime.pipeline.RuleBasedPlannerAutoConfiguration;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Puts the model-driven planner on the seam — when, and only when, a model is actually configured.
 *
 * <p>The condition is the point. {@link ChatClient.Builder} only exists once a deployment has put a
 * provider on the classpath, so adding this module to an application that has no model changes
 * nothing: the runtime's own rule-based planner stays in charge. Adding a provider is what turns
 * this on, which keeps "which model" a deployment decision rather than one this repository made on
 * the deployment's behalf.
 *
 * <p>The two orderings carry the rest:
 *
 * <ul>
 *   <li>{@code after} Spring AI's own chat-client auto-configuration, so the builder it contributes
 *       is already defined by the time {@link ConditionalOnBean} is evaluated.
 *   <li>{@code before} the runtime's default planner, so that planner sees a {@link ToolCallPlanner}
 *       already present and stands down. This is the arrangement JV-14 set up — the default yields
 *       rather than having to be out-ranked — and this module is the first thing to rely on it from
 *       outside the runtime.
 * </ul>
 *
 * <p>Both conditions are about who else is present, never about this module being special: a
 * deployment that declares its own planner keeps it.
 */
@AutoConfiguration
@AutoConfigureAfter(ChatClientAutoConfiguration.class)
@AutoConfigureBefore(RuleBasedPlannerAutoConfiguration.class)
@ConditionalOnBean(ChatClient.Builder.class)
@ConditionalOnMissingBean(ToolCallPlanner.class)
public class SpringAiPlannerAutoConfiguration {

    @Bean
    ToolCallPlanner springAiToolCallPlanner(ChatClient.Builder builder, ToolRegistry tools) {
        return new SpringAiToolCallPlanner(builder.build(), tools);
    }
}
