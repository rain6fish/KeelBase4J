// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the deployment can tell the runtime about the caller, before it means anything.
 *
 * <p>The identity SPI is deliberately two-sided: an {@link IdentityResolver} adapter turns whatever
 * identity evidence its deployment offers — a verified delegation token today, validated OIDC/JWT
 * claims or an LDAP bind result later — into the wire-shaped {@link Principal}. Evidence is carried
 * as a flat attribute map so the SPI does not privilege any one carrier; a richer carrier flattens
 * what it has (claims are named strings; groups are joined) and keeps the rest in its adapter.
 *
 * <p><b>Evidence is not identity, and it is never client-asserted.</b> The only factory here takes a
 * subject the request entry has already <em>verified</em>. There is deliberately no header carrier:
 * a value a client writes is not evidence of anything, and the header adapter that treated it as
 * such also let the client choose its own role (see {@link LocalIdentities} for why the role cannot
 * come from the caller at all).
 */
public record IdentityEvidence(Map<String, String> attributes) {

    /** Attribute key for the verified subject (protocol §3.2 {@code sub}). */
    public static final String SUBJECT = "subject";

    /** Attribute key for a verified OIDC subject, when the deployment issues one. */
    public static final String OIDC_SUBJECT = "oidcSubject";

    public IdentityEvidence {
        attributes = Map.copyOf(attributes);
    }

    /**
     * Evidence from an already-authenticated request: the entry verified who the caller is, and this
     * is what it verified. Nothing here came unverified from the caller — which is the whole point
     * of authenticating at the entry.
     */
    public static IdentityEvidence ofAuthenticatedSubject(String subject, String oidcSubject) {
        Map<String, String> attributes = new LinkedHashMap<>();
        putIfPresent(attributes, SUBJECT, subject);
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
