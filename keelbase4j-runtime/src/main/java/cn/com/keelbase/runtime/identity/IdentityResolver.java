// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

/**
 * The identity SPI — the pluggable seam between "who the deployment authenticated" and the
 * runtime's wire-shaped {@link Principal}.
 *
 * <p>KeelBase does not own identity infrastructure; it owns enterprise authorization semantics
 * (ADR-0004 D4). So this is a <em>single-method</em> seam an adapter satisfies for whatever identity
 * the deployment already has — request headers in the spike, enterprise OIDC/Keycloak/Sa-Token/LDAP
 * later. It adds no contract of its own: its output is the {@link Principal} projected onto the main
 * repo's frozen identity wire contracts ({@code delegation-token-claims} {@code sub}/{@code oidcSub},
 * {@code org-membership-scope}).
 *
 * <p>Authentication and request security belong to Spring Security (main repo
 * {@code docs/authorization-architecture.md} §7.1, ADR-0004 D3); an adapter for it implements this
 * interface and hands over the authenticated identity. The runtime never sees a credential.
 *
 * <p>Exactly one implementation must be a Spring bean.
 */
public interface IdentityResolver {

    /** Map validated evidence to the acting principal. Adapters reject evidence they cannot trust. */
    Principal resolve(IdentityEvidence evidence);
}
