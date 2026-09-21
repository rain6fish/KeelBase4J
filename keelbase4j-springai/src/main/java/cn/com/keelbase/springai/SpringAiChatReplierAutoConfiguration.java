// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import cn.com.keelbase.runtime.pipeline.ChatReplier;
import cn.com.keelbase.runtime.pipeline.ChatReplierAutoConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Puts the model-backed replier on the second seam — when, and only when, a model is configured.
 *
 * <p>A separate auto-configuration from the planner's, on purpose. That one is conditional on no
 * {@code ToolCallPlanner} being present, and a deployment is entitled to supply a planner without a
 * replier or the other way round; folding both into one class would tie two independent seams
 * together.
 *
 * <p>Same ordering rule as the planner: after Spring AI's own chat-client auto-configuration, so the
 * builder exists when the condition is evaluated; before the runtime's deterministic default, so that
 * default sees a {@link ChatReplier} already present and stands down.
 */
@AutoConfiguration
@AutoConfigureAfter(ChatClientAutoConfiguration.class)
@AutoConfigureBefore(ChatReplierAutoConfiguration.class)
@ConditionalOnBean(ChatClient.Builder.class)
@ConditionalOnMissingBean(ChatReplier.class)
public class SpringAiChatReplierAutoConfiguration {

    @Bean
    ChatReplier springAiChatReplier(ChatClient.Builder builder, ChatModel model) {
        return new SpringAiChatReplier(builder.build(), modelName(model));
    }

    /**
     * Which model answered, for the {@code model} field a caller reads. Spring AI does not promise
     * the options carry a name — a provider may leave it unset — so this says "unknown" rather than
     * inventing one.
     */
    private static String modelName(ChatModel model) {
        Object configured = model.getDefaultOptions().getModel();
        return configured == null || configured.toString().isBlank() ? "unknown" : configured.toString();
    }
}
