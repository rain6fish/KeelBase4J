// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.domain.FollowUp;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inspect and revoke AI write side effects (the recovery half of the trust boundary).
 *
 * <p>Both endpoints act as the authenticated caller: the list is narrowed to what that caller may
 * see, and a revoke is checked against them, never against a name the request carries.
 *
 * <p>The list answers the shape the console reads — {@code {total, page, limit, items}} with the
 * caller's {@code page} / {@code limit}, the page size capped as the reference caps it. Serving a
 * bare array meant a runtime-neutral console could read this endpoint on one runtime and not the
 * other, which is the whole thing the unified frontend is supposed to avoid.
 *
 * <p><b>What the items deliberately do not carry.</b> The console's model has fields this runtime has
 * no concept of — conversation ids, before/after snapshots, compensation groups, parent effects, and
 * the change summary derived from those snapshots. They are omitted rather than invented: {@code
 * conversationId} is sent as null because this runtime has no conversations, and the rest are simply
 * absent. A console column that reads one of them will be empty here, and that is the honest state
 * rather than a filled-in guess.
 */
@RestController
public class EffectController {

    /** The reference caps a page at 100; a caller cannot ask for the whole table by asking nicely. */
    private static final int MAX_PAGE_SIZE = 100;

    /** The runtime's only local entity, and so its only revocable target. */
    private static final String FOLLOW_UP = "follow_up";

    private final CurrentPrincipal principals;
    private final SideEffectService sideEffects;
    private final SideEffectRepository repository;
    private final FollowUpRepository followUps;

    public EffectController(CurrentPrincipal principals, SideEffectService sideEffects,
                            SideEffectRepository repository, FollowUpRepository followUps) {
        this.principals = principals;
        this.sideEffects = sideEffects;
        this.repository = repository;
        this.followUps = followUps;
    }

    @GetMapping("/ai/tool-effects")
    public Map<String, Object> list(@RequestParam(required = false) String userId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int limit) {
        Principal principal = principals.current();
        int size = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 1) - 1, size);

        Page<SideEffect> found;
        if (!principal.isManager()) {
            // A non-manager sees their own effects whatever they ask for — the filter is not theirs
            // to lift.
            found = repository.findByUserIdOrderByIdDesc(principal.userId(), pageable);
        } else if (userId != null) {
            found = repository.findByUserIdOrderByIdDesc(userId, pageable);
        } else {
            found = repository.findAll(pageable);
        }

        List<Map<String, Object>> items = found.getContent().stream().map(this::view).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", found.getTotalElements());
        body.put("page", page);
        body.put("limit", size);
        body.put("items", items);
        return body;
    }

    @DeleteMapping("/ai/tool-effects/{id}")
    public Map<String, Object> revoke(@PathVariable Long id) {
        Principal principal = principals.current();
        SideEffect effect = sideEffects.revoke(id, principal);
        // `revoked` is the frozen `revokeResult`'s required field, and `revokeStatus` says the same
        // thing in the contract's own vocabulary (the console reads that one). Both, because a
        // consumer holding the frozen object should not have to derive a required field from another.
        return Map.of(
                "effectId", effect.getId(),
                "resultType", effect.getResultType(),
                "revokeClass", effect.getRevokeClass(),
                "revokeStatus", effect.getRevokeStatus(),
                "revoked", "revoked".equals(effect.getRevokeStatus()));
    }

    /** A {@link LinkedHashMap}, not {@code Map.of}: several of these fields are legitimately null. */
    private Map<String, Object> view(SideEffect e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("toolName", e.getToolName());
        m.put("conversationId", null);
        m.put("resultType", e.getResultType());
        m.put("resultId", e.getResultId());
        m.put("argsHash", e.getArgsHash());
        m.put("createdAt", e.getCreatedAt().toString());

        Optional<FollowUp> target = target(e);
        m.put("targetExists", target.isPresent());
        m.put("targetSoftDeleted", target.map(t -> t.getDeletedAt() != null).orElse(false));
        // The only human-facing label a follow-up has is its note; the console shows it as the
        // target's title.
        m.put("targetTitle", target.map(FollowUp::getNote).orElse(null));

        m.put("revokeClass", e.getRevokeClass());
        // One stored column feeds two contract fields, and the contract gives the two different
        // vocabularies — `status` says what happened to the effect (executed / revoked /
        // revoking_external / revoke_failed), `revokeStatus` says where the revoke is (revoked /
        // compensating / revoke_failed, and null while none was ever attempted). The column's three
        // values map onto them without a guess: `executed` means no revoke has been tried, so the
        // revoke field is null; `revoked` means both; and `compensating` is a state of the *revoke*,
        // so the effect's own status stays `executed` until the compensation finishes.
        //
        // 一条存储列喂着契约的**两个**字段，而两者词表不同 —— `status` 说 effect 怎么了
        // （executed / revoked / revoking_external / revoke_failed），`revokeStatus` 说撤销走到哪
        // （revoked / compensating / revoke_failed，**从未撤销过则是 null**）。列上的三个取值不需要猜就能
        // 对上：`executed` ＝ 还没试过撤销 ⇒ 撤销字段为 null；`revoked` ＝ 两者都是；而 `compensating`
        // 是**撤销**的状态，所以 effect 自己的 status 在补偿结束前**仍是 `executed`**。
        String stored = e.getRevokeStatus();
        m.put("status", "revoked".equals(stored) ? "revoked" : "executed");
        m.put("revokeStatus", "executed".equals(stored) ? null : stored);

        // The frozen `traceItem`'s remaining three, answered with what this runtime has:
        //
        //   `afterSnapshot` — what the tool produced, stored when the write ran, because the row as it
        //   stands today is not the row the decision produced.
        //   `beforeSnapshot` — null, and that is the fact rather than a placeholder: nothing here
        //   overwrites an existing row, so there is no before to record.
        //   `compensationGroup` / `parentEffectId` — declared nullable, and null: this runtime has
        //   neither a compensation group nor a parent/child relation between effects, and inventing
        //   one would be a claim with nothing behind it.
        //
        // 冻结 `traceItem` 余下的三样，按本运行时**有的**答：
        //
        //   `afterSnapshot` —— 工具产出了什么，**在写跑成时存下**：今天这一行的样子，不是这次决策产出的样子。
        //   `beforeSnapshot` —— null，而这是**事实**而非占位：本运行时不改写既有行，所以没有「之前」可记。
        //   `compensationGroup` / `parentEffectId` —— 声明可空、而这里就是 null：本运行时既没有补偿组，
        //   也没有 effect 之间的父子关系，编一个出来只是**没有任何东西支撑的主张**。
        m.put("beforeSnapshot", null);
        m.put("afterSnapshot", e.getAfterSnapshot());
        m.put("compensationGroup", null);
        m.put("parentEffectId", null);

        // What the console renders the revoke button on, decided here rather than re-derived there.
        m.put("revocable",
                !"none".equals(e.getRevokeClass()) && "executed".equals(e.getRevokeStatus()));
        return m;
    }

    private Optional<FollowUp> target(SideEffect e) {
        return FOLLOW_UP.equals(e.getResultType()) ? followUps.findById(e.getResultId()) : Optional.empty();
    }
}
