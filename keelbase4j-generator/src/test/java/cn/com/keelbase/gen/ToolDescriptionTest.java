// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.gen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The same rule the runtime's tools are held to (CLAUDE.md hard rule 7), applied to the specs the
 * generator emits from.
 *
 * <p>A tool's description is not inert. The generator writes it into the generated tool class, and a
 * model attached to that application would be shown the name and the description and nothing else —
 * so a spec that says "只读，自动执行" in a description has published a governance verdict to whatever
 * reads it. The risk level is where that belongs.
 *
 * <p>This one leaked the same way the runtime's did (Java repo {@code a5b4065}) and was found while
 * fixing that one. It is a separate guard rather than a shared one because the two modules share no
 * test code: this list is a deliberate duplicate of {@code ToolCatalogueTest}'s, kept in step by hand.
 * A third place needing it is the point to extract it, not a second.
 *
 * <p><b>What this does not police</b>: a deployment's own spec. The generator emits from whatever spec
 * it is given, and a description written by hand elsewhere is that author's content — the generated
 * application is theirs to describe. What is guarded here is what this repository writes.
 *
 * <p>运行时那些工具所守的同一条规矩（CLAUDE.md 硬规则 7），用在生成器**据以发射的 spec** 上。
 *
 * <p>工具描述**不是惰性的**。生成器把它写进生成物的工具类，而接在那套应用上的模型，看到的就是名字与
 * 描述、别的什么都没有——所以一条描述里写着「只读，自动执行」的 spec，等于把一份治理结论**公布**给了
 * 读到它的任何东西。风险级才是它该待的地方。
 *
 * <p>这一处**和运行时那处一样泄了**（Java 仓 `a5b4065`），是在修那一处时发现的。它做成**独立的**守卫
 * 而不是共用的，是因为两个模块**不共享测试代码**：下面这份词表是有意与 `ToolCatalogueTest` 重复的，
 * 靠人工保持同步。**第三处**需要它的时候，才是该提取的时候——不是第二处。
 *
 * <p>**它管不到的地方**：部署方自己的 spec。生成器发射的是**交给它的** spec，而在别处手写的一条描述是
 * 那位作者的**内容**——生成出来的应用归他去描述。这里守的是**本仓写下的**东西。
 */
class ToolDescriptionTest {

    /**
     * The same vocabulary as the core guard's, plus the Chinese it needs; see the class comment for why
     * it is copied rather than shared.
     *
     * <p>A bare "确认" is deliberately absent: it is also the ordinary verb "to verify", so a
     * description like "确认客户信息" would be failed for doing its job — the same mistake the core
     * guard avoids by not banning "risk". What is listed is the phrasing that can only mean a
     * governance verdict.
     *
     * <p>与 core 那条同样的词表，外加它需要的中文；为何是**复制**而不是共享，见类注释。
     *
     * <p>裸的「确认」**有意不在列**：它同时是普通的动词「核实」，于是「确认客户信息」这种**正常干活的**
     * 描述会被判红——与 core 那条不禁「risk」是同一个道理。列在这里的，是**只可能表示治理结论**的措辞。
     */
    private static final List<String> GOVERNANCE_WORDS = List.of(
            "confirm", "approv", "read-only", "read only", "auto-execut", "revoke",
            "只读", "自动执行", "需确认", "人工确认", "审批", "风险级");

    private static final String REQUEST = """
            帮我做一个客户管理系统：有客户和跟进记录。销售只能看到自己负责的客户，经理能看到全部。
            AI 要能每天分析哪些客户风险高（只读）；AI 想给客户建跟进记录时，必须先让我确认；
            所有 AI 的操作都要能追溯。""";

    @Test
    void noSpecToolDescriptionTellsAReaderHowTheCallIsGoverned() {
        BusinessSpec spec = new BusinessSpecParser().parse(REQUEST);
        assertFalse(spec.tools().isEmpty(), "an empty tool list would make this true for no reason");

        List<String> offenders = new ArrayList<>();
        for (BusinessSpec.ToolSpec tool : spec.tools()) {
            String description = tool.description();
            assertTrue(description != null && !description.isBlank(),
                    tool.name() + " must describe itself: a blank description says nothing");
            for (String word : GOVERNANCE_WORDS) {
                if (description.contains(word)) {
                    offenders.add(tool.name() + " -> \"" + word + "\" in: " + description);
                }
            }
            if (description.matches("(?s).*\\bR[0-5]\\b.*")) {
                offenders.add(tool.name() + " -> a risk tier in: " + description);
            }
        }

        assertTrue(offenders.isEmpty(),
                "a description reaches whatever reads the generated tool, so it may say what the tool"
                        + " does and nothing about how the call is governed: " + offenders);
    }
}
