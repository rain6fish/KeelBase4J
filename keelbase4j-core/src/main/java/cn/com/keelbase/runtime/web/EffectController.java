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
        m.put("revokeStatus", e.getRevokeStatus());
        m.put("status", e.getRevokeStatus());
        // What the console renders the revoke button on, decided here rather than re-derived there.
        m.put("revocable",
                !"none".equals(e.getRevokeClass()) && "executed".equals(e.getRevokeStatus()));
        return m;
    }

    private Optional<FollowUp> target(SideEffect e) {
        return FOLLOW_UP.equals(e.getResultType()) ? followUps.findById(e.getResultId()) : Optional.empty();
    }
}
