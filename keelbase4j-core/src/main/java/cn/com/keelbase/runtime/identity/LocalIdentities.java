// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * This deployment's local identities: which user and which role a verified subject maps to.
 *
 * <p>This is where the <b>role</b> comes from, and it is the reason it is here rather than in the
 * token: the frozen delegation-token contract carries no role, precisely so that a token cannot
 * grant one. "Delegation never escalates privilege — the mapped local user's own permissions apply
 * after mapping" (protocol §3). A role read from the request would be the client granting itself
 * authority, which is what the old header adapter did.
 *
 * <p>Delivery <b>tier A</b> — declared identities, no user table. A tier B deployment replaces this
 * bean with a directory-backed source; nothing downstream changes, because everything reads it
 * through {@link #lookup(String)}.
 */
@Component
public class LocalIdentities {

    /**
     * A local identity: the user a subject maps to, the role they hold here, and where they sit in
     * the organization. The organization facts are what let a row range name a department — an
     * identity without them simply ranges over its own rows (see {@code runtime.scope}).
     *
     * @param orgId  the organization this user belongs to, or {@code null} when this deployment has none
     * @param deptId the department inside that organization, or {@code null}
     */
    public record Entry(String userId, String role, Long orgId, String orgName, Long deptId) {
    }

    private final Map<String, Entry> bySubject;

    /** A directory assembled by an alternative tier (B: directory-backed) without changing anything else. */
    public LocalIdentities(Map<String, Entry> bySubject) {
        this.bySubject = Map.copyOf(bySubject);
    }

    /** The tier-A identities this spike declares: one organization, a manager above two salespeople. */
    public LocalIdentities() {
        this(Map.of(
                "local:alice", new Entry("alice", Principal.ROLE_USER, 1L, "Acme", 11L),
                "local:bob", new Entry("bob", Principal.ROLE_USER, 1L, "Acme", 12L),
                "local:carol", new Entry("carol", Principal.ROLE_ADMIN, 1L, "Acme", 10L)));
    }

    /**
     * The local identity a verified subject maps to, or empty when this deployment does not know it.
     * Unknown means unknown: the caller is refused rather than defaulted to some nominal role.
     */
    public Optional<Entry> lookup(String subject) {
        return Optional.ofNullable(bySubject.get(subject));
    }
}
