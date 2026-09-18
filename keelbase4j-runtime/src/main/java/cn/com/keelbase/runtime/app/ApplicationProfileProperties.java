// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What this deployment is — declared, not inferred.
 *
 * <p>The F5 frontend contract surface ({@code GET /app/capabilities}) exists so a runtime-neutral
 * frontend can decide which navigation entries to show <em>without</em> asking which runtime it is
 * talking to (ADR-0002 Rev-8 / FE-1). That only works if the runtime can answer, and a governance
 * runtime has no way to know which business modules a deployment exposes. So the deployment states
 * it here.
 *
 * <p>These values are data; the <em>shape</em> is the frozen contract
 * ({@code capabilities.schema.json} / {@code app-provenance.schema.json} of the main repo). Changing
 * a default is not a contract change — adding or renaming a property here would be, because the
 * frontend keys off what comes out of the endpoint, not off this class.
 */
@ConfigurationProperties(prefix = "keelbase.app")
public class ApplicationProfileProperties {

    /** One of {@code full} / {@code small} / {@code lite} — the frozen preset enum (EASY-3). */
    private String preset = "full";

    /**
     * Feature switches, key → on/off. Open by design: the key set grows with the modules, which is
     * why the contract types it as an open boolean map rather than a fixed list.
     */
    private Map<String, Boolean> features = new LinkedHashMap<>(Map.of("crm", true));

    private Ai ai = new Ai();

    /**
     * Business modules projected to the frontend in the frozen {@code {id,label,description}} shape.
     *
     * <p>The default describes this repository's own demo deployment: an AI CRM backed by the
     * runtime's customer / follow-up domain and its two tools. It lives in Java rather than in
     * {@code application.properties} because the label and description are UI strings and therefore
     * non-ASCII — and {@code .properties} is read as ISO-8859-1, so a literal Chinese value there
     * arrives as mojibake even when the file's own bytes are correct UTF-8. A deployment that needs
     * to override these can do so, but must write any non-ASCII value as {@code \\uXXXX} escapes.
     */
    private List<BusinessModule> businessModules =
            new ArrayList<>(List.of(new BusinessModule("crm", "AI CRM", "客户、跟进与风险分析")));

    public String getPreset() {
        return preset;
    }

    public void setPreset(String preset) {
        this.preset = preset;
    }

    public Map<String, Boolean> getFeatures() {
        return features;
    }

    public void setFeatures(Map<String, Boolean> features) {
        this.features = features;
    }

    public Ai getAi() {
        return ai;
    }

    public void setAi(Ai ai) {
        this.ai = ai;
    }

    public List<BusinessModule> getBusinessModules() {
        return businessModules;
    }

    public void setBusinessModules(List<BusinessModule> businessModules) {
        this.businessModules = businessModules;
    }

    /**
     * AI availability. {@code enabled} and {@code providerConfigured} are separate on purpose: the
     * frontend has to tell "AI is part of this application" from "a model is actually wired up", and
     * this runtime has the former without the latter — the provider seam exists (JV-11 / JV-14) but
     * no provider is bound to it, because provider abstraction is still parked (ADR-0004).
     */
    public static class Ai {

        private boolean enabled = true;
        private boolean providerConfigured = false;
        private String provider = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isProviderConfigured() {
            return providerConfigured;
        }

        public void setProviderConfigured(boolean providerConfigured) {
            this.providerConfigured = providerConfigured;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }
    }

    /** One entry of {@code businessModules}. Kept a bean (not a record) so relaxed binding applies. */
    public static class BusinessModule {

        private String id;
        private String label;
        private String description;

        public BusinessModule() {
        }

        public BusinessModule(String id, String label, String description) {
            this.id = id;
            this.label = label;
            this.description = description;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
