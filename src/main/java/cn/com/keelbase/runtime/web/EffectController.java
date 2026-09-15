// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.effect.SideEffect;
import cn.com.keelbase.runtime.effect.SideEffectRepository;
import cn.com.keelbase.runtime.effect.SideEffectService;
import cn.com.keelbase.runtime.identity.IdentityEvidence;
import cn.com.keelbase.runtime.identity.IdentityResolver;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Inspect and revoke AI write side effects (the recovery half of the trust boundary). */
@RestController
public class EffectController {

    private final IdentityResolver identities;
    private final SideEffectService sideEffects;
    private final SideEffectRepository repository;

    public EffectController(IdentityResolver identities, SideEffectService sideEffects,
                            SideEffectRepository repository) {
        this.identities = identities;
        this.sideEffects = sideEffects;
        this.repository = repository;
    }

    @GetMapping("/ai/tool-effects")
    public List<Map<String, Object>> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
        Principal principal = identities.resolve(IdentityEvidence.ofHeaders(userId, role, oidcSubject));
        List<SideEffect> effects = principal.isManager()
                ? repository.findAll()
                : repository.findByUserIdOrderByIdDesc(principal.userId());
        return effects.stream().map(EffectController::view).toList();
    }

    @DeleteMapping("/ai/tool-effects/{id}")
    public Map<String, Object> revoke(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-Oidc-Sub", required = false) String oidcSubject) {
        Principal principal = identities.resolve(IdentityEvidence.ofHeaders(userId, role, oidcSubject));
        SideEffect effect = sideEffects.revoke(id, principal);
        return Map.of(
                "effectId", effect.getId(),
                "resultType", effect.getResultType(),
                "revokeClass", effect.getRevokeClass(),
                "revokeStatus", effect.getRevokeStatus());
    }

    private static Map<String, Object> view(SideEffect e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("toolName", e.getToolName());
        m.put("resultType", e.getResultType());
        m.put("resultId", e.getResultId());
        m.put("revokeClass", e.getRevokeClass());
        m.put("revokeStatus", e.getRevokeStatus());
        return m;
    }
}
