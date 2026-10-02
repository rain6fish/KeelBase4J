// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * The model name a reply reports: the configured one, or "unknown" when there is none.
 *
 * <p>Two answers, and the second is the one worth having a test for. A provider is free to leave the
 * model unnamed, and the field a caller reads has to say something — inventing a name there would put
 * a fact into the response that nothing established. This is the branch the class comment calls out,
 * and until now nothing asserted either half of it.
 *
 * <p>Reported through {@code getOptions()}, which in Spring AI 2.0 is the same object the deprecated
 * {@code getDefaultOptions()} returns — it does nothing but call this one. The upgrade that removes
 * the deprecated name is what the pair is guarded against, and the compiler is what guards it.
 *
 * 回复所报的模型名：配置的那个，或者没有时的 "unknown"。
 *
 * <p>两个答案，而值钱的测试是第二个。provider **可以**不给模型命名，而调用方读的那个字段总得说点什么——
 * 在那里编一个名字，等于往响应里放一个**没有任何东西确立过**的事实。这正是类注释点出的那个分支，而在此之前
 * 它**两半都没人断言**。
 *
 * <p>经 `getOptions()` 取值——在 Spring AI 2.0 里它与被弃用的 `getDefaultOptions()` 返回**同一个对象**
 * （后者除了调它什么都不做）。真正要防的是「移除那个弃用名」的那次升级，而守它的是**编译器**。
 */
class ModelNameTest {

    @Test
    void theConfiguredNameIsWhatACallerReads() {
        assertEquals("acme-model-1", SpringAiChatReplierAutoConfiguration.modelName(named("acme-model-1")));
    }

    /** A provider that leaves the name unset says nothing — and nothing is what the caller is told. */
    @Test
    void aModelThatLeavesTheNameUnsetIsReportedAsUnknown() {
        assertEquals("unknown", SpringAiChatReplierAutoConfiguration.modelName(nameless()));
        assertEquals("unknown", SpringAiChatReplierAutoConfiguration.modelName(named("   ")),
                "blank is unset: a caller cannot act on whitespace any more than on null");
    }

    /** A name, and nothing else: the model is never called by this path. */
    private static ChatModel named(String model) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("naming a model does not call it");
            }

            @Override
            public ChatOptions getOptions() {
                return ChatOptions.builder().model(model).build();
            }
        };
    }

    /** What a stub written as a lambda gets: Spring AI's default options, with no model named. */
    private static ChatModel nameless() {
        return prompt -> {
            throw new UnsupportedOperationException("naming a model does not call it");
        };
    }
}
