// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The registration pair: a governed write without a result type is refused and named; the same tool
 * with one is registered; a read is untouched.
 *
 * <p>These are the three readings A1 asks for, taken at the earliest layer. The status endpoint's
 * half lives in {@code IntegrationStatusResultTypeFailureTest}; here the registry itself is the
 * subject, so no Spring context is needed.
 *
 * 注册这一对读数：没有结果类型的受治理写被拒绝并点名；补上结果类型的同一个工具正常注册；读工具不受影响。
 *
 * <p>这就是 A1 要的三条读数，取在最早的一层。状态端点那一半在
 * {@code IntegrationStatusResultTypeFailureTest}；这里的主语是注册表本身，故不需要 Spring 上下文。
 */
class ToolDeclarationRegistrationTest {

    @Test
    void aGatedWriteWithoutAResultTypeIsRefusedAndNamed() {
        AiTool gated = tool("gated_write", "R3", null);

        ToolRegistry registry = new ToolRegistry(List.of(gated));

        assertEquals(List.of(), registry.all(), "a refused tool is not registered");
        assertEquals(List.of(gated), registry.refusedDeclarations(),
                "the refusal is kept so the status surface can name it");

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> registry.require("gated_write"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, refused.getStatusCode(),
                "a refused declaration is a deployment error, not an unknown tool");
        assertTrue(refused.getReason() != null && refused.getReason().contains("gated_write"),
                "the refusal names the tool: " + refused.getReason());
        assertTrue(refused.getReason().contains("resultType"),
                "the refusal names the missing declaration: " + refused.getReason());
        assertTrue(refused.getReason().contains("Fix"),
                "the refusal says how to fix it: " + refused.getReason());
    }

    @Test
    void theSameToolWithAResultTypeIsRegistered() {
        AiTool gated = tool("gated_write", "R3", "follow_up");

        ToolRegistry registry = new ToolRegistry(List.of(gated));

        assertEquals(List.of(gated), registry.all(), "declaring the result type removes the refusal");
        assertSame(gated, registry.require("gated_write"));
        assertEquals(List.of(), registry.refusedDeclarations());
    }

    @Test
    void aReadWithoutAResultTypeIsRegisteredAndServed() {
        AiTool read = tool("read_only", "R1", null);

        ToolRegistry registry = new ToolRegistry(List.of(read));

        assertEquals(List.of(read), registry.all(), "reads are not in A1's scope");
        assertSame(read, registry.require("read_only"));
        assertEquals(List.of(), registry.refusedDeclarations());
    }

    @Test
    void theTwoHigherGovernedLevelsAreRefusedToo() {
        AiTool approval = tool("approval_write", "R4", null);
        AiTool blocked = tool("blocked_write", "R5", null);

        ToolRegistry registry = new ToolRegistry(List.of(approval, blocked));

        assertEquals(List.of(), registry.all());
        assertEquals(List.of(approval, blocked), registry.refusedDeclarations(),
                "R4 waits for a second person and R5 is refused by policy; both are governed writes");
    }

    @Test
    void aBlankResultTypeCountsAsMissing() {
        AiTool gated = tool("gated_write", "R3", "   ");

        ToolRegistry registry = new ToolRegistry(List.of(gated));

        assertEquals(List.of(), registry.all(), "a blank label names no result type");
        assertEquals(List.of(gated), registry.refusedDeclarations());
    }

    @Test
    void anInvalidRiskLevelIsLeftToTheStatusDimension() {
        // R9 is not a governed write A1 can reason about; the status surface already reports the
        // invalid level. Refusing it here would make one defect look like two.
        //
        // R9 不是 A1 能推理的受治理写；状态面已经报了这个非法级别。在这里再拒绝一次，会让一处缺陷
        // 看起来像两处。
        AiTool invalid = tool("invalid_level", "R9", null);

        ToolRegistry registry = new ToolRegistry(List.of(invalid));

        assertEquals(List.of(invalid), registry.all());
    }

    private static AiTool tool(String name, String riskLevel, String resultType) {
        return new AiTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test double";
            }

            @Override
            public String riskLevel() {
                return riskLevel;
            }

            @Override
            public String resultType() {
                return resultType;
            }

            @Override
            public ToolResult execute(Map<String, Object> args, Principal principal) {
                return ToolResult.ok(Map.of("id", 1));
            }
        };
    }
}
