// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link DeterministicReplier} as a default, not a fixture — the arrangement JV-14 built for
 * {@link ToolCallPlanner}, applied to the second AI seam.
 *
 * <p>An auto-configuration so that it is processed <em>after</em> the application's own configuration:
 * a deployment that declares a {@link ChatReplier} — the Spring AI adapter does, when a model is
 * configured — is seen first and this one stands down. No {@code @Primary}, no exclusion list, and no
 * edit to this repository.
 */
@AutoConfiguration
@ConditionalOnMissingBean(ChatReplier.class)
public class ChatReplierAutoConfiguration {

    @Bean
    ChatReplier deterministicReplier() {
        return new DeterministicReplier();
    }
}
