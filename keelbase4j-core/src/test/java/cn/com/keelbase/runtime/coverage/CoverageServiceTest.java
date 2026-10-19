// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.governance.GovernancePolicyStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

/**
 * The statement is assembled from the deployment facts and the rows in the window.
 *
 * <p>The service is the only place that produces a fact set; these tests pin the mapping in both
 * directions — a host deployment declares the host gaps and a standalone one does not, and turning
 * the audit off turns the chain source into `absent` rather than into an empty success.
 *
 * <p>声明由部署事实与时间窗内的行组装而成。
 *
 * <p>本服务是**唯一生产事实集**的地方；这些测试把两个方向的映射钉住 —— 宿主部署声明宿主缺口、独立部署
 * 不声明；关掉审计后链源变成 `absent`，而不是一个空的成功。
 */
@SuppressWarnings("unchecked")
class CoverageServiceTest {

    private static final Instant UNTIL = Instant.parse("2026-10-19T12:00:00Z");
    private static final Instant SINCE = UNTIL.minus(30, ChronoUnit.DAYS);

    @Test
    void aHostDeploymentDeclaresTheHostGapsAndTheDerivedVerdict() {
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        SideEffectRepository effects = mock(SideEffectRepository.class);
        GovernancePolicyStore policy = mock(GovernancePolicyStore.class);
        when(policy.granularity()).thenReturn("all");
        when(auditLogs.count(any(Specification.class))).thenReturn(7L, 2L);
        when(effects.count(any(Specification.class))).thenReturn(3L, 1L, 1L);

        CoverageProperties properties = new CoverageProperties();
        properties.setHost("yudao");
        properties.setHostOwnsBusinessWrite(true);
        properties.setHostOwnAiPath(true);

        CoverageService service = new CoverageService(
                auditLogs, effects, policy, properties, new CoverageGaps());

        CoverageStatement statement = service.forScope(SINCE, UNTIL, "alice");

        assertEquals(List.of(
                        new CoverageStatement.Source("ai_audit_chain", "complete", 7L),
                        new CoverageStatement.Source("side_effect_ledger", "complete", 3L),
                        new CoverageStatement.Source("governance_policy", "complete", 1L)),
                statement.sources(),
                "only the sources that were actually read are listed");
        assertEquals(new CoverageStatement.Scope("period", SINCE.toString(), UNTIL.toString(), "alice"),
                statement.scope());
        assertTrue(statement.gaps().stream()
                        .anyMatch(gap -> "host-write-not-in-same-transaction".equals(gap.id())),
                "the host owns the business write, so the gap must be declared: " + statement.gaps());
        assertTrue(statement.gaps().stream()
                        .anyMatch(gap -> "host-ai-path-not-governed".equals(gap.id())),
                "the host has an AI path of its own, so the gap must be declared: " + statement.gaps());
        assertEquals("unknown", statement.verdict(),
                "`host-ai-path-not-governed` cannot name what is missing, so the verdict is unknown");
    }

    @Test
    void aStandaloneRuntimeDoesNotDeclareTheHostGaps() {
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        SideEffectRepository effects = mock(SideEffectRepository.class);
        GovernancePolicyStore policy = mock(GovernancePolicyStore.class);
        when(policy.granularity()).thenReturn("all");
        when(auditLogs.count(any(Specification.class))).thenReturn(0L, 0L);
        when(effects.count(any(Specification.class))).thenReturn(0L, 0L, 0L);

        CoverageService service = new CoverageService(
                auditLogs, effects, policy, new CoverageProperties(), new CoverageGaps());

        CoverageStatement statement = service.forScope(SINCE, UNTIL, null);

        assertTrue(statement.gaps().stream()
                        .noneMatch(gap -> gap.id().startsWith("host-")),
                "a standalone runtime has no host gap to declare: " + statement.gaps());
        assertNull(statement.scope().subjectRef(),
                "a period statement that named no caller keeps the subject open");
    }

    @Test
    void auditGranularityOffMakesTheChainSourceAbsent() {
        AuditLogRepository auditLogs = mock(AuditLogRepository.class);
        SideEffectRepository effects = mock(SideEffectRepository.class);
        GovernancePolicyStore policy = mock(GovernancePolicyStore.class);
        when(policy.granularity()).thenReturn("off");
        when(auditLogs.count(any(Specification.class))).thenReturn(0L, 0L);
        when(effects.count(any(Specification.class))).thenReturn(0L, 0L, 0L);

        CoverageService service = new CoverageService(
                auditLogs, effects, policy, new CoverageProperties(), new CoverageGaps());

        CoverageStatement statement = service.forScope(SINCE, UNTIL, null);

        assertEquals("absent", statement.sources().get(0).state(),
                "a source that is switched off is absent, not an empty success");
        assertTrue(statement.gaps().stream()
                        .anyMatch(gap -> "audit-granularity-off".equals(gap.id())),
                "turning the audit off must be declared: " + statement.gaps());
    }

    @Test
    void anUnknownHostIsRefusedAtConstruction() {
        CoverageProperties properties = new CoverageProperties();
        properties.setHost("somewhere");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new CoverageService(mock(AuditLogRepository.class),
                        mock(SideEffectRepository.class), mock(GovernancePolicyStore.class),
                        properties, new CoverageGaps()));

        assertTrue(refused.getMessage().contains("keelbase.coverage.host"),
                "the refusal must name the setting: " + refused.getMessage());
    }
}
