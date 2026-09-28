// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.security;

import cn.com.keelbase.runtime.identity.AuthenticatedSubject;
import jakarta.servlet.http.HttpServletRequest;

/**
 * How a request's caller is established — the seam an embedded deployment replaces (ADR-0017 D6, JV-35).
 *
 * <p>Standalone, the caller proves who they are with a delegation token, and that is the default here.
 * Embedded in a host, the caller has already been authenticated by the host's own machinery: the host
 * knows its session, its token, its user table. Making the host re-present a KeelBase delegation token
 * would mean two logins for one person and two identities in one request, which is the arrangement the
 * embedding exists to avoid. So the deployment answers this question instead, and everything downstream
 * is unchanged: this returns the same {@link AuthenticatedSubject} the token path produced, the filter
 * writes it into the security context the same way, and {@code CurrentPrincipal} and the whole
 * authorization chain never learn which deployment they are in.
 *
 * <p>One implementation must be a Spring bean. The core registers the delegation-token one on
 * {@code @ConditionalOnMissingBean}, so a deployment that declares its own replaces it rather than
 * colliding with it.
 *
 * <p>What this is <em>not</em> is a place to decide what the caller may do. It answers "who is this
 * request" and stops there, exactly as the delegation filter did (ADR-0004 D3) — the permission
 * question is answered by the frozen authorization contracts, downstream of {@code CurrentPrincipal}.
 */
public interface CallerAuthenticator {

    /**
     * The caller this request proves itself to be, or {@code null} when it proves nothing — in which
     * case the request is left unauthenticated and the chain answers 401. An implementation that
     * cannot verify what it was given returns {@code null} rather than a subject it did not verify.
     */
    AuthenticatedSubject authenticate(HttpServletRequest request);
}
