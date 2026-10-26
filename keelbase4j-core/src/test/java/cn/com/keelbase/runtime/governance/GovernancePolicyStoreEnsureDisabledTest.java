// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * `ensureDisabled` adds the missing overrides and changes nothing an administrator already wrote.
 *
 * <p>The host calls it when it registers a host-assembled external tool, so the tool starts behind a
 * gate the engine really applies. It must be idempotent, must not race the Policy Center's
 * whole-document `set`, and must never overwrite a decision a person made.
 *
 * <p>`ensureDisabled` 补上缺失的覆盖，且**不改**管理员已经写下的任何东西。
 *
 * <p>宿主在注册宿主装配的外部工具时调用它，于是工具一开始就在引擎真的施加的闸门后面。它必须幂等、不得与策略中心
 * 的整份 `set` 撞车，也绝不能覆盖人做过的决定。
 */
class GovernancePolicyStoreEnsureDisabledTest {

    private static final String TOOL = "mcp__server__write";

    @Test
    void theFirstCallDisablesTheToolAndKeepsTheAuditSetting() {
        Fixture fixture = new Fixture();

        fixture.store.ensureDisabled(List.of(TOOL));

        Map<String, Object> saved = fixture.savedPolicy();
        assertEquals(Map.of("enabled", false), saved.get("tools") instanceof Map<?, ?> tools
                ? tools.get(TOOL) : null, "the tool starts disabled");
        assertEquals(Map.of("granularity", "all"), saved.get("audit"),
                "the audit granularity is carried over, not reset");
        verify(fixture.policies, times(1)).save(any(GovernancePolicyRow.class));
    }

    @Test
    void aSecondCallChangesNothing() {
        Fixture fixture = new Fixture();
        fixture.store.ensureDisabled(List.of(TOOL));

        fixture.store.ensureDisabled(List.of(TOOL));

        verify(fixture.policies, times(1)).save(any(GovernancePolicyRow.class));
    }

    @Test
    void anExistingOverrideIsNeverOverwritten() {
        Fixture fixture = new Fixture(
                "{\"tools\":{\"" + TOOL + "\":{\"enabled\":true}},\"audit\":{\"granularity\":\"all\"}}");

        fixture.store.ensureDisabled(List.of(TOOL));

        verify(fixture.policies, never()).save(any(GovernancePolicyRow.class));
        assertTrue(fixture.store.current().get("tools").toString().contains("enabled=true"),
                "a person's decision stays");
    }

    @Test
    void anUnknownToolIsRefusedRatherThanStored() {
        Fixture fixture = new Fixture();

        assertThrows(ResponseStatusException.class,
                () -> fixture.store.ensureDisabled(List.of("not_registered")));
        verify(fixture.policies, never()).save(any(GovernancePolicyRow.class));
    }

    /** One policy row and one registered tool, with the repositories standing in for JPA. */
    private static final class Fixture {

        private final GovernancePolicyRepository policies = mock(GovernancePolicyRepository.class);
        private final GovernancePolicyHistoryRepository history =
                mock(GovernancePolicyHistoryRepository.class);
        private final AtomicReference<GovernancePolicyRow> row = new AtomicReference<>();
        private final GovernancePolicyStore store;

        Fixture() {
            this(null);
        }

        Fixture(String existingJson) {
            if (existingJson != null) {
                row.set(new GovernancePolicyRow(existingJson));
            }
            when(policies.findAll()).thenAnswer(invocation ->
                    row.get() == null
                            ? List.<GovernancePolicyRow>of()
                            : List.of(row.get()));
            when(policies.save(any(GovernancePolicyRow.class))).thenAnswer(invocation -> {
                GovernancePolicyRow saved = invocation.getArgument(0);
                row.set(saved);
                return saved;
            });
            when(history.findFirstByOrderByIdDesc()).thenReturn(Optional.empty());
            ToolRegistry registry = new ToolRegistry(List.of(tool(TOOL)));
            store = new GovernancePolicyStore(policies, history, registry);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> savedPolicy() {
            return (Map<String, Object>) Json.parse(row.get().getPolicyJson());
        }

        private static AiTool tool(String name) {
            return new AiTool() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public String description() {
                    return "external write";
                }

                @Override
                public String riskLevel() {
                    return "R2";
                }

                @Override
                public ToolResult execute(Map<String, Object> args, Principal principal) {
                    return ToolResult.ok("ok");
                }
            };
        }
    }
}
