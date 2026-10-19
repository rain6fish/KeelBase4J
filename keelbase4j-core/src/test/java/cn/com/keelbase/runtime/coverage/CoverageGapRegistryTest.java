// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The registry answers the facts it is given, and nothing is hardcoded in the service.
 *
 * <p>These are the AW-3 proof obligations moved to the host side: the two host gaps appear when the
 * deployment declares host facts, they do not appear for a standalone runtime, a gap added to the
 * mirror shows up without touching the service, and a predicate naming an unknown fact is refused
 * instead of silently applying to nothing.
 *
 * <p>登记表按给它的那组事实作答，且服务里没有写死任何缺口。
 *
 * <p>这是挪到宿主侧的 AW-3 证明义务：部署方声明宿主事实时两条宿主缺口出现；独立运行时不出现；
 * 往镜像里加一条缺口无需改服务就会出现；谓词点名未知事实时被拒绝，而不是悄悄地对谁都不适用。
 */
class CoverageGapRegistryTest {

    @Test
    void aHostDeploymentGetsBothHostGaps() {
        CoverageGaps registry = new CoverageGaps();
        List<String> ids = registry.applicable(facts("yudao", true, true)).stream()
                .map(CoverageGap::id)
                .toList();

        assertTrue(ids.contains("host-write-not-in-same-transaction"),
                "the host owns the business write, so the non-transactional window applies: " + ids);
        assertTrue(ids.contains("host-ai-path-not-governed"),
                "the host has an AI path of its own, so that path is outside this package: " + ids);
        assertEquals("unknown", CoverageGaps.deriveVerdict(registry.applicable(facts("yudao", true, true))),
                "an `unknown` gap wins over `partial`: " + ids);
    }

    @Test
    void aStandaloneRuntimeDoesNotClaimTheHostGaps() {
        CoverageGaps registry = new CoverageGaps();
        List<String> ids = registry.applicable(facts("none", false, false)).stream()
                .map(CoverageGap::id)
                .toList();

        assertFalse(ids.contains("host-write-not-in-same-transaction"), "no host owns a write here: " + ids);
        assertFalse(ids.contains("host-ai-path-not-governed"), "there is no host AI path here: " + ids);
    }

    @Test
    void auditGranularityOffIsVisibleInTheStatement() {
        CoverageGaps registry = new CoverageGaps();
        Map<String, Object> off = facts("none", false, false);
        off.put("auditGranularity", "off");

        assertTrue(registry.applicable(off).stream()
                        .anyMatch(gap -> "audit-granularity-off".equals(gap.id())),
                "turning the audit off must be declared, not left as silence");
    }

    @Test
    void aGapAddedToTheRegistryAppearsWithoutTouchingTheService() {
        CoverageGaps registry = CoverageGaps.fromJson("""
                {"gaps":[{
                  "id":"proof-gap","severity":"low","affects":"whole_deployment",
                  "verdictImpact":"partial","appliesWhen":[],"statement":"a proof entry",
                  "origin":"test"
                }]}
                """);

        assertEquals(List.of("proof-gap"),
                registry.applicable(facts("none", false, false)).stream().map(CoverageGap::id).toList(),
                "an always-applicable entry needs no code change to be declared");
    }

    @Test
    void anUnknownFactIsRefusedAtLoadTime() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> CoverageGaps.fromJson("""
                        {"gaps":[{
                          "id":"typo","severity":"low","affects":"this_package",
                          "verdictImpact":"partial",
                          "appliesWhen":[{"fact":"hostOwnsTheWrites","equals":true}],
                          "statement":"a typo","origin":"test"
                        }]}
                        """));

        assertTrue(refused.getMessage().contains("hostOwnsTheWrites"),
                "the refusal must name the fact it could not evaluate: " + refused.getMessage());
    }

    @Test
    void theRevisionIdentifiesTheRegistryItWasComputedAgainst() {
        CoverageGaps first = CoverageGaps.fromJson("{\"gaps\":[]}");
        CoverageGaps second = CoverageGaps.fromJson("{\"gaps\":[]}");
        CoverageGaps changed = CoverageGaps.fromJson("{\"gaps\":[],\"note\":\"changed\"}");

        assertEquals(first.registryRevision(), second.registryRevision(),
                "the same registry content must answer the same revision");
        assertNotEquals(first.registryRevision(), changed.registryRevision(),
                "a changed registry document must answer a different revision");
    }

    private static Map<String, Object> facts(String host, boolean ownsWrite, boolean ownAiPath) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("runtime", "java");
        facts.put("host", host);
        facts.put("hostOwnsBusinessWrite", ownsWrite);
        facts.put("hostOwnAiPath", ownAiPath);
        facts.put("auditGranularity", "all");
        facts.put("sharedCacheAcrossDeployments", false);
        facts.put("nonDiscreteActionSurfacesExposed", false);
        facts.put("packageHasExternalAnchor", false);
        facts.put("packageHasEffectWithoutSnapshot", false);
        facts.put("packageHasOpenDispute", false);
        facts.put("packageHasStalledRevoke", false);
        facts.put("packageHasChatRow", false);
        return facts;
    }
}
