// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.app.ApplicationProfileProperties;
import cn.com.keelbase.runtime.app.ApplicationProfileProperties.BusinessModule;
import cn.com.keelbase.runtime.tool.AiTool;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The runtime's self-description: what this application is, and which capabilities it exposes.
 *
 * <p>These are the F5 endpoints of the frozen Full profile
 * ({@code docs/protocols/conformance-profile.md} §2.2). Together with the response envelope they are
 * what lets <em>one</em> frontend run against either runtime: the frontend branches on capability
 * — never on which runtime answered (ADR-0002 Rev-8 / FE-1). Both are unauthenticated by design,
 * because the frontend has to learn what this system offers before anyone has a token; that is the
 * same reason the reference marks them public.
 *
 * <p>Nothing here is a governance decision. Whether an AI behaviour may happen is decided by the
 * frozen authorization contracts, and neither endpoint reports on a caller.
 *
 * <p><b>On the {@code /api/v1} prefix.</b> The reference serves these under its global prefix and
 * URI version, and the frontend's API base defaults to {@code /api/v1}, so it asks for
 * {@code /api/v1/app/capabilities}. Serving that exact path is what makes this runtime a drop-in
 * for the frontend rather than one needing its base reconfigured. The rest of this runtime's
 * surface still sits at the root — aligning <em>that</em> is the remaining work, not this class.
 */
@RestController
@RequestMapping("/api/v1/app")
public class AppInfoController {

    private final ApplicationProfileProperties profile;
    private final ToolRegistry tools;

    public AppInfoController(ApplicationProfileProperties profile, ToolRegistry tools) {
        this.profile = profile;
        this.tools = tools;
    }

    /** {@code data} of {@code GET /app/capabilities} — shape frozen in {@code capabilities.schema.json}. */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("preset", profile.getPreset());
        body.put("features", profile.getFeatures());
        body.put("ai", aiBlock());
        body.put("businessModules", moduleBlock());
        return body;
    }

    /** {@code data} of {@code GET /app/provenance} — shape frozen in {@code app-provenance.schema.json}. */
    @GetMapping("/provenance")
    public Map<String, Object> provenance() {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("preset", profile.getPreset());
        runtime.put("businessModules", moduleBlock());
        runtime.put("aiToolFingerprint", toolFingerprint());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", sourceBlock());
        body.put("runtime", runtime);
        return body;
    }

    private Map<String, Object> aiBlock() {
        ApplicationProfileProperties.Ai ai = profile.getAi();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("enabled", ai.isEnabled());
        block.put("providerConfigured", ai.isProviderConfigured());
        block.put("provider", ai.getProvider());
        return block;
    }

    private List<Map<String, Object>> moduleBlock() {
        return profile.getBusinessModules().stream().map(module -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", module.getId());
            entry.put("label", module.getLabel());
            entry.put("description", module.getDescription());
            return entry;
        }).toList();
    }

    /**
     * Counts only — never the tools' arguments, risk levels or revoke classes, which stay
     * server-side. A writer is a tool that declares a business result type; a reader declares none
     * (see {@link AiTool#resultType()}).
     */
    private Map<String, Object> toolFingerprint() {
        List<AiTool> all = tools.all();
        long writeTools = all.stream().filter(tool -> tool.resultType() != null).count();
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("total", all.size());
        fingerprint.put("readTools", all.size() - writeTools);
        fingerprint.put("writeTools", writeTools);
        return fingerprint;
    }

    /**
     * Build-side provenance. The reference expands {@code .keelbase/manifest.json} here, which
     * {@code keelbase init} writes on the TypeScript side. This runtime is not produced by that
     * generator and has no such manifest, so it says so rather than inventing an origin — the
     * contract requires the field to be an object, not a particular content.
     */
    private Map<String, Object> sourceBlock() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("manifestPresent", false);
        return source;
    }
}
