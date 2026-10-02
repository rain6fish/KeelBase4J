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
        Map<String, PermissionCapabilityList.Resource> bySubject = new LinkedHashMap<>();
        for (AuthorizationRules.Rule rule : rules.rulesFor(principal)) {
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
        return new PermissionCapabilityList(principal.role(),
                principal.isManager() ? PermissionCapabilityList.BASIS_ADMIN : PermissionCapabilityList.BASIS_USER,
                resources);
    }

    private static boolean grants(AuthorizationRules.Rule rule, String subject) {
        return PermissionCapabilityList.SUBJECT_ALL.equals(rule.subject()) || rule.subject().equals(subject);
    }
}
