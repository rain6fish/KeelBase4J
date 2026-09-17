// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The default identity adapter: turns what the request entry authenticated into the runtime's
 * {@link Principal}.
 *
 * <p>It reads only the <em>verified</em> subject and looks the user and role up locally
 * ({@link LocalIdentities}). That is the whole difference from the header adapter it replaces: a
 * caller can no longer state who it is, nor what it may do. It can present a token, and this
 * deployment decides what that token's subject means here.
 *
 * <p>An adapter for a real directory (OIDC, LDAP, Sa-Token) implements this same seam and maps its
 * verified claims onto the same frozen contracts; nothing downstream changes.
 */
@Component
public class SecurityIdentityResolver implements IdentityResolver {

    private final LocalIdentities directory;

    public SecurityIdentityResolver(LocalIdentities directory) {
        this.directory = directory;
    }

    @Override
    public Principal resolve(IdentityEvidence evidence) {
        String subject = evidence.attribute(IdentityEvidence.SUBJECT);
        if (subject == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no authenticated subject");
        }
        LocalIdentities.Entry entry = directory.lookup(subject)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "this deployment has no identity mapped to the presented subject"));
        return new Principal(entry.userId(), entry.role(),
                evidence.attribute(IdentityEvidence.OIDC_SUBJECT), null);
    }
}
