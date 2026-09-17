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

    /** A local identity: the user id a subject maps to, and the role that user holds here. */
    public record Entry(String userId, String role) {
    }

    private final Map<String, Entry> bySubject;

    /** A directory assembled by an alternative tier (B: directory-backed) without changing anything else. */
    public LocalIdentities(Map<String, Entry> bySubject) {
        this.bySubject = Map.copyOf(bySubject);
    }

    /** The tier-A identities this spike declares. */
    public LocalIdentities() {
        this(Map.of(
                "local:alice", new Entry("alice", Principal.ROLE_USER),
                "local:bob", new Entry("bob", Principal.ROLE_USER),
                "local:carol", new Entry("carol", Principal.ROLE_ADMIN)));
    }

    /**
     * The local identity a verified subject maps to, or empty when this deployment does not know it.
     * Unknown means unknown: the caller is refused rather than defaulted to some nominal role.
     */
    public Optional<Entry> lookup(String subject) {
        return Optional.ofNullable(bySubject.get(subject));
    }
}
