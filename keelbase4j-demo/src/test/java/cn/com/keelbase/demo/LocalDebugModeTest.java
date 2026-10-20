// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.KeelBase4JApplication;
import cn.com.keelbase.runtime.pipeline.ChatReplier;
import cn.com.keelbase.runtime.pipeline.ChatReply;
import cn.com.keelbase.runtime.pipeline.DeterministicReplier;
import cn.com.keelbase.runtime.pipeline.LocalDebugReplier;
import cn.com.keelbase.runtime.pipeline.RuleBasedPlanner;
import cn.com.keelbase.runtime.pipeline.ToolCallPlanner;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Local debug mode on the deployment that actually carries a provider: no API key, no model bean, the
 * two deterministic seams, and an explicit refusal for what only a model could answer.
 *
 * <p>This lives in the demo module rather than the runtime on purpose. The runtime has no provider, so
 * it starts offline for reasons unrelated to this mode; only a classpath with a provider proves that
 * the switch is what lets it start. Without the switch, this context fails while DeepSeek's chat model
 * is created — {@code "API key must be set"}.
 *
 * <p>本地调试模式跑在**真的带着 provider** 的部署上：没有 API key、没有模型 bean、两条确定性接缝，以及
 * 只有模型才答得出的请求被明确拒绝。
 *
 * <p>它放在 demo 模块而不是 runtime，是刻意的。runtime 没有 provider，它离线起得来与本模式无关；只有
 * 带 provider 的 classpath 才证明「是这个开关让它起得来」。没有开关时，本上下文会在 DeepSeek 的 chat model
 * 创建处失败 —— {@code "API key must be set"}。
 */
@SpringBootTest(
        classes = KeelBase4JApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "keelbase.local-debug.enabled=true")
class LocalDebugModeTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    ToolCallPlanner planner;

    @Autowired
    ChatReplier replier;

    @Test
    void theProviderBearingDeploymentStartsWithNoKeyAndTakesTheDeterministicSeams() {
        assertInstanceOf(RuleBasedPlanner.class, planner,
                "local debug mode routes by the existing deterministic rules");
        assertInstanceOf(LocalDebugReplier.class, replier,
                "and replies through the deterministic replier, with its unsupported case spelled out");
    }

    @Test
    void theProviderIsNotConfiguredAtAll() {
        assertThrows(NoSuchBeanDefinitionException.class, () -> context.getBean(
                        org.springframework.ai.chat.model.ChatModel.class),
                "the provider's chat auto-configuration is off, so it never demands an API key");
    }

    @Test
    void aFreeFormQuestionIsRefusedAsUnsupportedRatherThanAnswered() {
        ChatReply reply = replier.reply("今天天气怎么样？", List.of(), null);

        assertTrue(reply.text().contains("Offline debug mode"), reply.text());
        assertTrue(reply.text().contains("free-form answer"), reply.text());
        assertEquals(DeterministicReplier.PROVIDER, reply.provider(),
                "a caller can tell this from a model's answer without guessing");
        assertEquals(DeterministicReplier.MODEL, reply.model());
    }
}
