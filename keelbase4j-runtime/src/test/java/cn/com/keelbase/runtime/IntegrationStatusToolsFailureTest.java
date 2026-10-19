// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The tools dimension red, and only it: a tool declaring a risk level outside R0–R5.
 *
 * <p>The bad declaration is the one the engine cannot gate — {@code RiskLevel.strategy} refuses the
 * level — so the health surface must report it rather than let the call fail later or, worse, count
 * the tool as registered and move on. The confirmation dimension deliberately <em>skips</em> the bad
 * tool: a bad level is the tools block's finding, and repeating it there would make one defect look
 * like two. The other three dimensions are asserted green to show the report is surgical.
 *
 * 工具维度红、且只有它：一个声明了 R0–R5 之外风险级的工具。
 *
 * <p>这个坏声明正是引擎**无法门控**的那种 —— `RiskLevel.strategy` 拒绝该级别 —— 故健康面必须把它报出来，
 * 而不是让调用稍后失败、或更糟地把它算作已注册然后继续。确认维度**刻意跳过**这个坏工具：坏级别是工具块的
 * 发现，在那里重复一遍会让一处缺陷看起来像两处。另外三个维度断言为绿，以证明报告是**外科式**的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@Import(IntegrationStatusToolsFailureTest.BrokenToolDeployment.class)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:status-tools;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class IntegrationStatusToolsFailureTest {

    private static final String ADMIN = "carol";

    @TestConfiguration(proxyBeanMethods = false)
    static class BrokenToolDeployment {

        /**
         * A tool whose declaration the frozen table does not know. It is still a registered bean, which
         * is the point: the registry accepts it, and only a reading of the declaration catches it.
         *
         * 一个冻结表不认识的声明。它仍然是一个已注册的 bean，而这正是重点：注册表接受它，只有**读一遍声明**
         * 才抓得住。
         */
        @Bean
        AiTool brokenTool() {
            return new AiTool() {
                @Override
                public String name() {
                    return "broken_tool";
                }

                @Override
                public String description() {
                    return "declares a risk level outside the frozen table";
                }

                @Override
                public String riskLevel() {
                    return "R9";
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    return ToolResult.ok(null);
                }
            };
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AuditService audit;

    @Value("${keelbase.delegation.secret}")
    String secret;

    @Test
    void theBadDeclarationTurnsOnlyTheToolsDimensionRedAndNamesTheTool() {
        IntegrationStatusProbe.seedAudit(audit);
        Map<String, Object> report = IntegrationStatusProbe.data(
                IntegrationStatusProbe.get(rest, IntegrationStatusProbe.PATH, ADMIN, secret));

        Map<String, Object> tools = IntegrationStatusProbe.block(report, "tools");
        assertEquals("error", tools.get("status"));
        assertTrue(((List<?>) tools.get("invalidRiskLevels")).contains("broken_tool"));
        assertEquals(4, IntegrationStatusProbe.number(tools, "total"), "the bad tool is still registered");

        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) IntegrationStatusProbe.block(report, "health").get("errors");
        assertTrue(errors.stream().anyMatch(error -> error.contains("broken_tool")),
                "the error must name the tool: " + errors);

        assertEquals("ok", IntegrationStatusProbe.block(report, "confirmation").get("status"));
        assertEquals("ok", IntegrationStatusProbe.block(report, "audit").get("status"));
        assertEquals("ok", IntegrationStatusProbe.block(report, "delegation").get("status"));
        assertEquals("error", IntegrationStatusProbe.block(report, "health").get("status"));
    }
}
