// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.authz;

import cn.com.keelbase.protocol.PermissionCapabilityList;
import cn.com.keelbase.protocol.PermissionDecision;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The authorization decision function — self-built, and the reason KeelBase owns its own
 * authorization semantics (ADR-0004 D4 / Considered Option E).
 *
 * <p>It answers two questions in the vocabulary of the main repo's frozen contracts: {@link #decide}
 * produces a {@code permission-decision}, {@link #describe} produces a
 * {@code permission-capability-list}. Both take their rules from {@link AuthorizationRules}, so the
 * rule source can change (tier A declarations → tier B tables) without touching the semantics.
 *
 * <p>No external policy engine is embedded: the decisions are a few hundred lines of rule matching,
 * and buying an engine would buy exactly that part while costing a parallel contract
 * (ADR-0004 Option E).
 */
@Service
public class PermissionAuthorizer {

    /** The action a row-level check asks about; the capability grant itself is action-wide. */
    public static final String ACTION_READ = "read";

    private final AuthorizationRules rules;

    public PermissionAuthorizer(AuthorizationRules rules) {
        this.rules = rules;
    }

    /** The frozen {@code permission-decision} for {@code action × subject}. */
    public PermissionDecision decide(Principal principal, String action, String subject) {
        PermissionCapabilityList.Resource capability = capabilityFor(principal, subject);
        boolean allowed = capability != null && capability.actions().contains(action);
        String reason;
        if (allowed) {
            reason = PermissionCapabilityList.SUBJECT_ALL.equals(subject)
                    ? PermissionDecision.REASON_ALLOWED_ALL
                    : PermissionDecision.REASON_ALLOWED_OWN;
        } else {
            reason = principal.isManager()
                    ? PermissionDecision.REASON_DENIED_ADMIN
                    : PermissionDecision.REASON_DENIED_USER;
        }
        return new PermissionDecision(action, subject, allowed, reason,
                allowed ? null : PermissionDecision.DENIED_BY_CASL);
    }

    /**
     * The capability this principal holds on {@code subject}, or {@code null} when nothing is granted.
     * The row-level scope lives here rather than on the decision, which is how the frozen contracts
     * split it.
     *
     * <p>Every rule that grants the subject contributes its actions, which is the union
     * {@link #describe} states. A source that declares one rule per permission — what a table-backed
     * tier B does, one row per host permission — would otherwise have all but the first of them
     * ignored, and the caller would be refused an action it was granted. The scope and the reason are
     * the first grant's, the same way {@code describe} keeps them.
     *
     * <p>授予该 subject 的**每一条**规则都贡献它的动作——这正是 {@link #describe} 声明的并集。一个按
     * 权限逐条声明规则的源（表后端的档 B 就是——宿主一条权限一行）否则除第一条外全被忽略，调用者会被拒掉
     * 它**本已获授**的动作。范围与理由取**第一条**授予的，与 `describe` 的取法一致。
     */
    public PermissionCapabilityList.Resource capabilityFor(Principal principal, String subject) {
        boolean found = false;
        boolean own = false;
        String reason = null;
        List<String> granted = new ArrayList<>();
        for (AuthorizationRules.Rule rule : rules.rulesFor(principal)) {
            if (!grants(rule, subject)) {
                continue;
            }
            if (!found) {
                found = true;
                own = rule.ownerField() != null;
                if (own) {
                    reason = PermissionCapabilityList.REASON_RESOURCE_OWN;
                } else if (PermissionCapabilityList.SUBJECT_ALL.equals(rule.subject())) {
                    reason = PermissionCapabilityList.REASON_RESOURCE_ALL;
                } else {
                    reason = PermissionCapabilityList.REASON_RESOURCE_UNRESTRICTED;
                }
            }
            granted.addAll(PermissionCapabilityList.normalizeActions(rule.action()));
        }
        if (!found) {
            return null;
        }
        return new PermissionCapabilityList.Resource(subject,
                own ? PermissionCapabilityList.SCOPE_OWN : PermissionCapabilityList.SCOPE_ALL,
                PermissionCapabilityList.normalizeActions(granted), reason);
    }

    /** The frozen {@code permission-capability-list}: what this identity may do, and on what basis. */
    public PermissionCapabilityList describe(Principal principal) {
        List<AuthorizationRules.Rule> granted = rules.rulesFor(principal);
        Map<String, PermissionCapabilityList.Resource> bySubject = new LinkedHashMap<>();
        for (AuthorizationRules.Rule rule : granted) {
            if (PermissionCapabilityList.SUBJECT_ALL.equals(rule.subject())) {
                bySubject.put(rule.subject(), new PermissionCapabilityList.Resource(
                        rule.subject(), PermissionCapabilityList.SCOPE_ALL,
                        PermissionCapabilityList.normalizeActions(rule.action()),
                        PermissionCapabilityList.REASON_RESOURCE_ALL));
                continue;
            }
            PermissionCapabilityList.Resource existing = bySubject.get(rule.subject());
            if (existing != null) {
                // Several rules on one subject grant the union of their actions (capability = union).
                List<String> union = new ArrayList<>(existing.actions());
                union.addAll(PermissionCapabilityList.normalizeActions(rule.action()));
                bySubject.put(rule.subject(), new PermissionCapabilityList.Resource(
                        existing.subject(), existing.scope(),
                        PermissionCapabilityList.normalizeActions(union), existing.reason()));
                continue;
            }
            boolean own = rule.ownerField() != null;
            bySubject.put(rule.subject(), new PermissionCapabilityList.Resource(
                    rule.subject(),
                    own ? PermissionCapabilityList.SCOPE_OWN : PermissionCapabilityList.SCOPE_ALL,
                    PermissionCapabilityList.normalizeActions(rule.action()),
                    own ? PermissionCapabilityList.REASON_RESOURCE_OWN
                            : PermissionCapabilityList.REASON_RESOURCE_UNRESTRICTED));
        }

        List<PermissionCapabilityList.Resource> resources = new ArrayList<>(bySubject.values());
        resources.sort(Comparator.comparing(PermissionCapabilityList.Resource::subject));
        return new PermissionCapabilityList(principal.role(), basisOf(principal, granted), resources);
    }

    /**
     * Why this identity holds what it holds, in the words the capability list carries.
     *
     * <p>The two tier-A strings answer for an administrator and a normal user. A deployment that
     * grants a read-only identity the governance read faces produces a third kind (JV-63), and
     * answering "a normal user who may manage their own resources" for it would be false in the
     * direction that matters — a console would offer writes the identity cannot make.
     *
     * <p>「审计读者」按**构造**判定，而不是按角色名：角色词表只有 {@code user}/{@code admin}，审计读者并不
     * 是其中一档。判据是它**每一条**授予都只含 {@code read}，且至少一条落在治理读面上。
     *
     * <p>这个身份凭什么持有它持有的东西，用能力清单自己的话作答。
     *
     * <p>档 A 那两条字符串分别答管理员与普通用户。一个被授予治理读面的只读身份会产出**第三种**（JV-63），
     * 而拿「可管理本人拥有资源的普通用户」去答它，会错在**要紧的方向**上——控制台会给出这个身份根本做不了的写操作。
     */
    private static String basisOf(Principal principal, List<AuthorizationRules.Rule> granted) {
        if (principal.isManager()) {
            return PermissionCapabilityList.BASIS_ADMIN;
        }
        return isAuditReader(granted) ? PermissionCapabilityList.BASIS_AUDITOR : PermissionCapabilityList.BASIS_USER;
    }

    /**
     * Every grant is {@code read}, and at least one is on a governance read face — which is what an
     * audit reader is by construction (JV-63 B8(a)): it holds no write capability at all, so it
     * cannot approve or execute by construction rather than by a separate guard.
     *
     * <p>每一条授予都是 {@code read}，且至少一条落在治理读面上 —— 这正是审计读者**按构造**的定义
     * （JV-63 B8(a)）：它一项写能力都没有，所以「不能审批、不能执行」是构造出来的，而不是靠另一道守卫。
     */
    private static boolean isAuditReader(List<AuthorizationRules.Rule> granted) {
        boolean onAGovernanceReadFace = false;
        for (AuthorizationRules.Rule rule : granted) {
            for (String action : PermissionCapabilityList.normalizeActions(rule.action())) {
                if (!ACTION_READ.equals(action)) {
                    return false;
                }
            }
            onAGovernanceReadFace |= GovernanceReadSubjects.ALL.contains(rule.subject());
        }
        return onAGovernanceReadFace;
    }

    private static boolean grants(AuthorizationRules.Rule rule, String subject) {
        return PermissionCapabilityList.SUBJECT_ALL.equals(rule.subject()) || rule.subject().equals(subject);
    }
}
