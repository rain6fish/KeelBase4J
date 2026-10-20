// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.com.keelbase.runtime.pipeline.LocalDebugEnvironmentPostProcessor;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The switch's two readings: off leaves the provider selector alone, on forces every provider's chat
 * auto-configuration off. The second half is what lets a deployment that carries a provider start
 * without an API key.
 *
 * <p>开关的两个读数：关时不动 provider 选择键，开时关掉每一家的 chat 自动配置。后一半正是「带着
 * provider 的部署能在没有 API key 时起得来」的原因。
 */
class LocalDebugEnvironmentPostProcessorTest {

    private final LocalDebugEnvironmentPostProcessor processor = new LocalDebugEnvironmentPostProcessor();

    @Test
    void offByDefaultLeavesTheProviderSelectorAlone() {
        StandardEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertNull(environment.getProperty("spring.ai.model.chat"),
                "with the mode off, the deployment's own provider choice is untouched");
        assertNull(environment.getProperty("spring.ai.chat.client.enabled"),
                "and the chat client keeps its own default");
    }

    @Test
    void turningItOnForcesEveryProvidersChatAutoConfigurationOff() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "keelbase.local-debug.enabled", "true",
                "spring.ai.model.chat", "deepseek")));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertEquals("none", environment.getProperty("spring.ai.model.chat"),
                "a provider selected elsewhere must not survive local debug mode");
        assertEquals("false", environment.getProperty("spring.ai.chat.client.enabled"),
                "and the chat client must stand down rather than demand a model that is gone");
    }
}
