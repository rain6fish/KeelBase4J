// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the deployment can tell the runtime about the caller, before it means anything.
 *
 * <p>The identity SPI is deliberately two-sided: an {@link IdentityResolver} adapter turns whatever
 * identity evidence its deployment offers — request headers today, validated OIDC/JWT claims or an
 * LDAP bind result later — into the wire-shaped {@link Principal}. Evidence is carried as a flat
 * attribute map so the SPI does not privilege any one carrier; a richer carrier flattens what it has
 * (claims are named strings; groups are joined) and keeps the rest in its adapter.
 *
 * <p><b>Evidence is not identity.</b> Nothing here is trusted until the adapter has validated it and
 * mapped it into the frozen contracts — in particular an OIDC subject must come from a token whose
 * signature the adapter verified, never from a header a client set.
 */
public record IdentityEvidence(Map<String, String> attributes) {

    /** Attribute key for the caller's user id, as carried by the header adapter. */
    public static final String USER_ID = "userId";

    /** Attribute key for the caller's role hint, as carried by the header adapter. */
    public static final String ROLE = "role";

    /** Attribute key for the authenticated OIDC subject, when the deployment has one. */
    public static final String OIDC_SUBJECT = "oidcSubject";

    public IdentityEvidence {
        attributes = Map.copyOf(attributes);
    }

    /** Evidence from the spike's header carrier; absent headers are simply not present. */
    public static IdentityEvidence ofHeaders(String userId, String role, String oidcSubject) {
        Map<String, String> attributes = new LinkedHashMap<>();
        putIfPresent(attributes, USER_ID, userId);
        putIfPresent(attributes, ROLE, role);
        putIfPresent(attributes, OIDC_SUBJECT, oidcSubject);
        return new IdentityEvidence(attributes);
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    public String attribute(String key) {
        return attributes.get(key);
    }
}
