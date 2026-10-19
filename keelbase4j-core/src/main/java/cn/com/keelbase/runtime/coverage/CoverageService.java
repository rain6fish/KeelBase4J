// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.coverage;

import cn.com.keelbase.runtime.audit.AuditLog;
import cn.com.keelbase.runtime.audit.AuditLogRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.governance.GovernancePolicyStore;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Assembles the coverage statement for a scope — "what was read, and what this reading cannot show".
 *
 * <p>This is the reader half of {@link CoverageGaps}: that class says what the gaps are and computes
 * the verdict; this class is the only place that <b>produces</b> a fact set, so a reader never has
 * to guess which caveats apply. Nothing here decides which gaps exist.
 *
 * <p>The facts split in two. Deployment facts (runtime, host, granularity) come from configuration
 * and the governance policy. Scope facts (`packageHas…`) are counted from the rows in the window,
 * because "does this period contain an effect whose change was never captured" is not answerable
 * from configuration. Two facts are deliberately answered the same way they are in the reference:
 * `sharedCacheAcrossDeployments` is a deployment declaration, and `packageHasExternalAnchor` is
 * {@code false} because this runtime has no anchor endpoint — a statement says the chain alone is
 * not enough instead of pretending it is.
 *
 * <p>为某个口径组装覆盖声明 —— 「读了什么，以及这份读法**看不了**什么」。
 *
 * <p>它是 {@link CoverageGaps} 的读者那一半：那个类说缺口**是什么**、并算出结论；本类是**唯一生产**
 * 事实集的地方，于是读者不必猜哪几条告诫适用。这里**不决定**有哪些缺口。
 *
 * <p>事实分两半。部署事实（runtime / host / 粒度）读自配置与治理策略。口径事实（`packageHas…`）
 * 从时间窗内的行数点出来，因为「这一期里有没有一次变更没被捕获」不是配置能回答的。两条事实有意与参照实现
 * 同答：`sharedCacheAcrossDeployments` 是部署声明；`packageHasExternalAnchor` 取 {@code false}，
 * 因为本运行时**没有锚端点** —— 声明于是说「光有链不够」，而不是假装够了。
 */
@Component
public class CoverageService {

    private static final Set<String> HOSTS = Set.of("none", "yudao", "ruoyi");

    private final AuditLogRepository auditLogs;
    private final SideEffectRepository effects;
    private final GovernancePolicyStore policy;
    private final CoverageProperties properties;
    private final CoverageGaps gaps;

    public CoverageService(AuditLogRepository auditLogs, SideEffectRepository effects,
                           GovernancePolicyStore policy, CoverageProperties properties,
                           CoverageGaps gaps) {
        if (!HOSTS.contains(properties.getHost())) {
            throw new IllegalStateException(
                    "keelbase.coverage.host must be one of " + HOSTS + ", not `" + properties.getHost()
                            + "` — a statement that cannot name the host cannot say which host gaps"
                            + " apply");
        }
        this.auditLogs = auditLogs;
        this.effects = effects;
        this.policy = policy;
        this.properties = properties;
        this.gaps = gaps;
    }

    public CoverageStatement forScope(Instant since, Instant until, String userId) {
        String caller = userId == null || userId.isBlank() ? null : userId;
        Specification<AuditLog> auditWhere = auditWithin(since, until, caller);
        Specification<SideEffect> effectWhere = effectsWithin(since, until, caller);

        String granularity = policy.granularity();

        long auditRows = auditLogs.count(auditWhere);
        long effectRows = effects.count(effectWhere);
        long chatRows = auditLogs.count(auditWhere.and(
                (root, query, cb) -> cb.equal(root.get("action"), "chat")));
        // A create legitimately has no `beforeSnapshot` (there was no row), so only a missing
        // `afterSnapshot` is unambiguous evidence that the change was never captured. An update
        // whose tool omitted its `beforeSnapshot` cannot be told apart from a create from the row
        // alone, so it is not counted — a caveat that cannot be checked is worse than a narrower one.
        // 新建的 effect 本来就没有 `beforeSnapshot`（那时没有行），故只有缺 `afterSnapshot` 才是
        // 「变更没被捕获」的**无歧义**证据。工具漏交 `beforeSnapshot` 的更新，从这一行上分不出它与新建，
        // 故**不计入** —— 一条无法核实的告诫比一条更窄的告诫更糟。
        long withoutSnapshot = effects.count(effectWhere.and(
                (root, query, cb) -> cb.isNull(root.get("afterSnapshot"))));
        // This runtime records no dispute state on an effect; `packageHasOpenDispute` is therefore
        // always false here, and the corresponding registry condition remains for the runtime that
        // does record it.
        // 本运行时不记录副作用的争议状态；故 `packageHasOpenDispute` 在这里恒为 false，登记表里那条
        // 条件留给**确实记录它**的运行时。
        long stalledRevoke = effects.count(effectWhere.and(
                (root, query, cb) -> cb.equal(root.get("revokeStatus"), "compensating")));

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("runtime", "java");
        facts.put("host", properties.getHost());
        facts.put("hostOwnsBusinessWrite", properties.isHostOwnsBusinessWrite());
        facts.put("hostOwnAiPath", properties.isHostOwnAiPath());
        facts.put("auditGranularity", granularity);
        facts.put("sharedCacheAcrossDeployments", properties.isSharedCacheAcrossDeployments());
        facts.put("nonDiscreteActionSurfacesExposed",
                properties.isNonDiscreteActionSurfacesExposed());
        facts.put("packageHasExternalAnchor", false);
        facts.put("packageHasEffectWithoutSnapshot", withoutSnapshot > 0);
        facts.put("packageHasOpenDispute", false);
        facts.put("packageHasStalledRevoke", stalledRevoke > 0);
        facts.put("packageHasChatRow", chatRows > 0);

        // Only the sources this statement actually read — the list is "what was consulted", not
        // "what exists in the system".
        // 只列**这份声明真的读了**的源 —— 这张表是「问过谁」，不是「系统里有什么」。
        List<CoverageStatement.Source> sources = List.of(
                new CoverageStatement.Source("ai_audit_chain",
                        switch (granularity) {
                            case "off" -> "absent";
                            case "write" -> "partial";
                            default -> "complete";
                        },
                        auditRows),
                new CoverageStatement.Source("side_effect_ledger", "complete", effectRows),
                new CoverageStatement.Source("governance_policy", "complete", 1L));

        List<CoverageGap> applicable = gaps.applicable(facts);
        return new CoverageStatement(
                gaps.registryRevision(),
                new CoverageStatement.Scope("period", since.toString(), until.toString(), caller),
                sources,
                CoverageGaps.deriveVerdict(applicable),
                applicable.stream()
                        .map(gap -> new CoverageStatement.Gap(
                                gap.id(), gap.severity(), gap.affects(), gap.statement()))
                        .toList());
    }

    private static Specification<AuditLog> auditWithin(Instant since, Instant until, String userId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.between(root.get("createdAt"), since, until));
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Specification<SideEffect> effectsWithin(Instant since, Instant until, String userId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.between(root.get("createdAt"), since, until));
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
