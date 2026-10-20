// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Turns every Spring AI chat-model auto-configuration off when local debug mode is on.
 *
 * <p>Registering the deterministic seams is not enough on its own: a provider starter on the classpath
 * creates its chat model eagerly, and DeepSeek (among others) throws {@code "API key must be set"} while
 * doing so — before any planner is asked anything. Spring AI gates every provider's chat
 * auto-configuration on the same selector, {@code spring.ai.model.chat}; giving it a value no provider
 * matches ("none") is the provider-independent way to say "no model here".
 *
 * <p>The chat client needs its own switch, too. With no chat model left, Spring AI's
 * {@code ChatClientAutoConfiguration} would still try to build a {@code ChatClient.Builder} around one
 * and fail on the missing bean; {@code spring.ai.chat.client.enabled=false} lets it stand down. That
 * is also what keeps the Spring AI adapter's planner, replier and task runner from being offered a
 * builder that no longer means anything.
 *
 * <p>The property source is added first, so it wins over a provider selected in a properties file or on
 * the command line. That is the point of the mode: a deployment that turned it on asked for no model,
 * and a half-on state would be the one thing worse than either end.
 *
 * <p>本地调试模式打开时，关掉所有 Spring AI chat 模型自动配置。
 *
 * <p>只注册确定性接缝不够：classpath 上的 provider starter 会**急切**创建它的 chat model，DeepSeek（以及
 * 其它家）在创建时抛 {@code "API key must be set"} —— 早于任何规划器被问到。Spring AI 用同一个选择键
 * {@code spring.ai.model.chat} 给每一家的 chat 自动配置把关；给它一个没有 provider 会匹配的值（"none"），
 * 就是与 provider 无关地说「这里没有模型」。
 *
 * <p>chat client 还要自己的开关。模型都没了，Spring AI 的 {@code ChatClientAutoConfiguration} 仍会围着
 * 一个模型去建 {@code ChatClient.Builder}、并因缺 bean 而失败；{@code spring.ai.chat.client.enabled=false}
 * 让它让位。这也让 Spring AI 适配器的 planner、replier、task runner 不再拿到一个已经没有意义的 builder。
 *
 * <p>属性源被放在**最前**，故它胜过 properties 文件或命令行里选中的 provider。这正是本模式的意义：打开它的
 * 部署要的就是没有模型，而「半开」是唯一比两端都糟的状态。
 */
public class LocalDebugEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** The switch a deployment sets. Default off. */
    public static final String ENABLED = "keelbase.local-debug.enabled";

    /** Spring AI's provider-independent chat-model selector. */
    public static final String CHAT_MODEL_SELECTOR = "spring.ai.model.chat";

    /** Spring AI's chat-client switch; off, it does not demand a chat model. */
    public static final String CHAT_CLIENT_ENABLED = "spring.ai.chat.client.enabled";

    private static final String SOURCE_NAME = "keelbaseLocalDebug";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty(ENABLED, Boolean.class, false)) {
            return;
        }
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, Map.of(
                CHAT_MODEL_SELECTOR, "none",
                CHAT_CLIENT_ENABLED, "false")));
    }
}
