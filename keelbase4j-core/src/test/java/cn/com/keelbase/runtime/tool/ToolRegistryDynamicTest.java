// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.identity.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dynamic registration keeps the declaration rule, and the origin split keeps host-assembled tools
 * out of the deployment's model catalogue.
 *
 * <p>These are the two properties a host relies on when it registers MCP and SKILL tools while it
 * assembles a turn: the tool becomes executable by the engine, but it does not become part of what
 * the deployment's planners offer to every model.
 *
 * <p>动态注册保留声明规则；来源之分把宿主装配的工具挡在部署的模型目录之外。
 *
 * <p>这两条是宿主在装配一轮会话时注册 MCP 与 SKILL 工具所依赖的性质：工具变得能被引擎执行，但**不会**成为
 * 部署的规划器交给每个模型的那一份目录。
 */
class ToolRegistryDynamicTest {

    @Test
    void hostAssembledToolsAreRegisteredButNotInTheCatalogue() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("deployment_read", "R1", null)));

        registry.register(tool("mcp__server__search", "R1", null), ToolRegistry.Origin.HOST_ASSEMBLED);

        assertEquals(List.of("deployment_read", "mcp__server__search"),
                registry.all().stream().map(AiTool::name).toList(),
                "the engine, policy and inventory must see every registered tool");
        assertEquals(List.of("deployment_read"),
                registry.catalogue().stream().map(AiTool::name).toList(),
                "a host-assembled tool belongs to one agent's binding, not the deployment catalogue");
        assertTrue(registry.contains("mcp__server__search"));
    }

    @Test
    void aHostAssembledToolCannotShadowTheDeployments() {
        AiTool deployment = tool("same_name", "R1", null);
        ToolRegistry registry = new ToolRegistry(List.of(deployment));

        registry.register(tool("same_name", "R2", null), ToolRegistry.Origin.HOST_ASSEMBLED);

        assertSame(deployment, registry.require("same_name"),
                "an MCP server named after a host tool must not take its place");
    }

    @Test
    void aDeploymentRegistrationReplacesAHostAssembledOne() {
        ToolRegistry registry = new ToolRegistry(List.of());
        registry.register(tool("same_name", "R1", null), ToolRegistry.Origin.HOST_ASSEMBLED);
        AiTool deployment = tool("same_name", "R1", null);

        registry.register(deployment, ToolRegistry.Origin.DEPLOYMENT);

        assertSame(deployment, registry.require("same_name"));
        assertEquals(List.of("same_name"), registry.catalogue().stream().map(AiTool::name).toList());
    }

    @Test
    void aGovernedWriteWithoutAResultTypeIsStillRefused() {
        ToolRegistry registry = new ToolRegistry(List.of());

        registry.register(tool("external_write", "R3", null), ToolRegistry.Origin.HOST_ASSEMBLED);

        assertFalse(registry.contains("external_write"), "the declaration rule is not bypassed by origin");
        assertThrows(ResponseStatusException.class, () -> registry.require("external_write"),
                "asking for it must explain the refusal rather than answer 'unknown tool'");
    }

    private static AiTool tool(String name, String riskLevel, String resultType) {
        return new AiTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool " + name;
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
