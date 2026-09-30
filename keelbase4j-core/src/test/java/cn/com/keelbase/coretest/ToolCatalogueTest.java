// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What a model is allowed to be told about a tool (CLAUDE.md hard rule 7).
 *
 * <p>A tool's name and its description are the whole of its model-facing surface — the Spring AI
 * adapter builds the catalogue out of exactly those two — so whatever a description says, a model has
 * been told. Governance metadata (the risk level, whether a call needs confirmation, the revoke class)
 * must not be among it: a model that knows which calls are gated can be argued with about the gate,
 * and the gate is not its to reason about. It proposes; the runtime disposes.
 *
 * <p><b>This reads the tools the runtime actually ships, not a stub.</b> The adapter's own test writes a
 * description by hand, and the one it wrote happened to contain none of this vocabulary — so it passed
 * while a real tool's description said "requires confirmation" and travelled to the model verbatim. A
 * guard is only worth having if it reads the thing that ships.
 *
 * <p><b>{@code risk} is deliberately not on the list.</b> {@code analyze_customer_risk} analyses risk:
 * that is its subject, not its governance. What is forbidden is the vocabulary of <em>how a call is
 * handled</em>, not a domain word that happens to look similar. A guard that banned "risk" would fail
 * on a tool doing its job and be deleted within the week.
 *
 * <p>**模型被允许知道工具的什么**（CLAUDE.md 硬规则 7）。
 *
 * <p>工具的名字与描述，就是它面向模型的**全部**表面——Spring AI 适配器的目录正是拿这两样拼出来的——所以
 * 描述里写了什么，就是**告诉了模型什么**。治理元数据（风险级、是否需确认、撤销档）**不得**在其中：一个
 * 知道哪些调用被门控的模型，可以被**说服去议论那道门**，而那道门不归它推理。它提议，运行时分派。
 *
 * <p>**这条断言读的是运行时真正发出的工具，不是桩。** 适配器自己的测试手写了一条描述，而它恰好不含这套
 * 词汇——于是它一路绿着，而某个真实工具的描述写着「requires confirmation」，**逐字**送到了模型面前。
 * 守卫只有读**真正发货的那个东西**才值得存在。
 *
 * <p>**{@code risk} 有意不在名单里。** {@code analyze_customer_risk} 分析的就是**风险**：那是它的主题，
 * 不是它的治理。被禁的是「**这次调用会被怎么处理**」这套词汇，不是一个长得像的领域词。一条禁掉「risk」的
 * 守卫会在一个**正常干活的工具**上失败，然后一周内被人删掉。
 */
@SpringBootTest(classes = CoreTestApplication.class,
        properties = {
                "keelbase.audit.hmac-key=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.secret=0011223344556677889900112233445566778899001122334455667788990011",
                "keelbase.delegation.audience=keelbase4j",
                "spring.jpa.hibernate.ddl-auto=validate"
        })
class ToolCatalogueTest {

    /**
     * The vocabulary of <em>how a call is handled</em>. Lowercase, matched as substrings so that every
     * inflection is covered by one entry; the risk tiers are matched separately because they are tokens.
     */
    private static final List<String> GOVERNANCE_WORDS = List.of(
            "confirm", "approv", "read-only", "read only", "auto-execut", "revoke");

    @Autowired
    ToolRegistry registry;

    @Test
    void noToolDescriptionTellsAModelHowTheCallIsGoverned() {
        List<AiTool> tools = registry.all();
        assertFalse(tools.isEmpty(), "an empty catalogue would make this assertion true for no reason");

        List<String> offenders = new ArrayList<>();
        for (AiTool tool : tools) {
            String description = tool.description();
            assertTrue(description != null && !description.isBlank(),
                    tool.name() + " must describe itself: a blank description says nothing to a model");
            String lower = description.toLowerCase();
            for (String word : GOVERNANCE_WORDS) {
                if (lower.contains(word)) {
                    offenders.add(tool.name() + " -> \"" + word + "\" in: " + description);
                }
            }
            if (description.matches("(?s).*\\bR[0-5]\\b.*")) {
                offenders.add(tool.name() + " -> a risk tier in: " + description);
            }
        }

        assertTrue(offenders.isEmpty(),
                "a description is sent to the model word for word, so it may say what the tool does and"
                        + " nothing about how the call is governed: " + offenders);
    }
}
