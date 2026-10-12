// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.app.ApplicationProfileProperties;
import cn.com.keelbase.runtime.app.ApplicationProfileProperties.BusinessModule;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the block says about a deployment, and — the half worth guarding — what it does not.
 *
 * <p>The block exists so that "what does this system offer" has an answer the system itself gave. The
 * guard is the second test: a prompt that carried a risk level would be handing the model the one kind
 * of fact the adapter is forbidden to send it, and the only way to keep that from creeping back in is
 * to assert its absence.
 *
 * <p>Pinned without a context, the same reason {@code modelName} is tested that way: the assembly is a
 * pure function of two things the deployment declares, and standing up Spring to answer a question
 * about a string would only make the test slower and less specific.
 *
 * <p>这一块说的是**一个部署是什么**，以及 —— 值钱的是后一半 —— 它**不说**什么。
 *
 * <p>这一块存在的理由，是让「这个系统提供什么」有一个**系统自己给出**的答案。守它的是第二个测试：一个带风险级的
 * prompt，等于把适配器**被禁止**发出去的那一类事实交给模型，而要让它不再悄悄长回来，唯一的办法就是**断言它不在**。
 *
 * <p>不起上下文就钉住，理由与 `modelName` 那样测**是同一条**：这段组装是**部署声明的两样东西**的纯函数，为了
 * 一个字符串的问题把 Spring 支起来，只会让测试更慢、更不具体。
 */
class ApplicationFactsTest {

    @Test
    void whatTheDeploymentDeclaredIsWhatTheModelIsTold() {
        ApplicationProfileProperties profile = new ApplicationProfileProperties();
        profile.setPreset("small");
        profile.setFeatures(new LinkedHashMap<>(Map.of("crm", true, "sms", false)));
        profile.setBusinessModules(List.of(new BusinessModule("crm", "AI CRM", "客户与跟进")));

        String facts = SpringAiChatReplier.applicationFacts(profile,
                new ToolRegistry(List.of(tool("navigate_console_page", "Open a page of this console"))));

        assertTrue(facts.contains("preset: small"), facts);
        assertTrue(facts.contains("crm"), facts);
        assertTrue(facts.contains("AI CRM — 客户与跟进"), facts);
        assertTrue(facts.contains("navigate_console_page — Open a page of this console"), facts);
        assertFalse(facts.contains("sms"),
                "a feature that is off is not something this system offers: " + facts);
    }

    /**
     * Asked for nothing, it says nothing — no heading with an empty list under it, which would read as
     * "this system offers nothing": a claim nobody made.
     *
     * <p>Note what is <em>not</em> a way to get here. A default-constructed profile is not "nothing":
     * the runtime ships a default of its own (preset {@code full}, the CRM module), so a deployment
     * that declares no properties still has those stated for it. Only an absent profile and an empty
     * tool set are the empty case.
     *
     * 问它要「没有」，它就答「没有」—— 不会留下一个**底下是空清单**的抬头，那读起来像「这个系统什么都不提供」：
     * 一句**没人说过**的话。
     *
     * <p>注意**不构成**这里那种情况的，是另一样东西：一个**默认构造**的档案**不是**「没有」——运行时自带一份
     * 默认（preset `full` 与 CRM 模块），故什么都没声明的部署**仍然**有这些被替它说出来。只有档案缺席、且工具集为空，
     * 才落到空的那一支。
     */
    @Test
    void askedForNothingItSaysNothing() {
        assertEquals("", SpringAiChatReplier.applicationFacts(null, null));
        assertEquals("", SpringAiChatReplier.applicationFacts(null, new ToolRegistry(List.of())));
    }

    /**
     * The governance metadata stays server-side. The tool below declares a write at R3, and none of
     * that reaches the block — the planner's rule ("risk level, confirmation requirement and revoke
     * class are not sent to a model") holds for this second seam too, because a model shown a risk
     * level is a model that can be talked into reasoning about it.
     *
     * 治理元数据留在服务端。下面那个工具声明了一次 R3 的写，而没有一样到达这一块 —— planner 那条规矩
     * （「风险级、是否需确认、撤销档**不发给模型**」）对**第二条缝**同样成立，因为一个**看见了风险级**的模型，
     * 就是一个**可以被说动去就它推理**的模型。
     */
    @Test
    void noGovernanceMetadataReachesTheBlock() {
        String facts = SpringAiChatReplier.applicationFacts(new ApplicationProfileProperties(),
                new ToolRegistry(List.of(tool("create_customer", "Create a customer"))));

        assertTrue(facts.contains("create_customer"), facts);
        assertFalse(facts.contains("R3"), "the declared risk level stays server-side: " + facts);
        assertFalse(facts.contains("confirm"), "so does whether it needs a human: " + facts);
        assertFalse(facts.contains("local_compensate"), "and so does its revoke class: " + facts);
    }

    private static AiTool tool(String name, String description) {
        return new AiTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String riskLevel() {
                return "R3";
            }

            @Override
            public String resultType() {
                return "customer";
            }

            @Override
            public ToolResult execute(Map<String, Object> args, Principal principal) {
                throw new UnsupportedOperationException("never called by this path");
            }
        };
    }
}
