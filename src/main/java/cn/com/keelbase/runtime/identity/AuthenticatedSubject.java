// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

/**
 * The caller as established at the request entry: what a verified delegation token proves, and
 * nothing more.
 *
 * <p>It carries no role, and that is the point. The frozen token contract has no role claim, and
 * "delegation never escalates privilege — the mapped local user's own permissions apply after
 * mapping" (protocol §3). A role taken from the request — which is what the spike's header adapter
 * used to do — would be exactly the escalation the protocol forbids.
 *
 * <p>It lives here rather than beside the Spring Security wiring so the dependency runs one way:
 * the security layer produces this, the identity layer consumes it, and identity knows nothing
 * about Spring Security.
 *
 * @param subject     the unified identity mapping key (protocol §3.2 {@code sub})
 * @param oidcSubject an SSO subject when the deployment issues one, else {@code null}
 */
public record AuthenticatedSubject(String subject, String oidcSubject) {
}
