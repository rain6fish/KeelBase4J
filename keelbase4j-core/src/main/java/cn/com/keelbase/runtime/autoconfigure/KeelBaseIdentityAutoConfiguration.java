// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.autoconfigure;

import cn.com.keelbase.runtime.identity.IdentityResolver;
import cn.com.keelbase.runtime.identity.LocalIdentities;
import cn.com.keelbase.runtime.identity.SecurityIdentityResolver;
import cn.com.keelbase.runtime.scope.Departments;
import cn.com.keelbase.runtime.security.CallerAuthenticator;
import cn.com.keelbase.runtime.security.DelegationTokenAuthenticator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The two identity defaults a deployment replaces when it embeds this runtime (ADR-0017 D6, JV-35).
 *
 * <p>Both answers this runtime needs about identity come from the deployment: <em>how is this request
 * authenticated</em> ({@link CallerAuthenticator}) and <em>who is that person here, with what role and
 * what organization</em> ({@link IdentityResolver}). Standalone, the runtime answers both itself — a
 * delegation token, and a directory declared in {@code LocalIdentities}. Embedded, the host already
 * knows both, and asking the host's user to also hold a KeelBase token, or to be described in a
 * KeelBase directory, would be a second identity system beside the host's. That is the arrangement the
 * "absolutely never three permission systems" warning is about.
 *
 * <p>So they are beans with defaults rather than components with opinions. Both carry
 * {@code @ConditionalOnMissingBean}: a deployment that declares its own gets it, and nothing here runs.
 * That is not a convenience — it is the whole mechanism. Component scanning cannot express it (a
 * {@code @ConditionalOnMissingBean} on a scanned class is not evaluated the way it is on an
 * auto-configuration), so a default that must be replaceable has to be registered here.
 *
 * <p>Two consequences worth knowing. A deployment that replaces {@code CallerAuthenticator} never has
 * {@code keelbase.delegation.secret} resolved, so an embedded host is not made to hold a KeelBase
 * secret it has no use for. And a deployment that replaces {@code IdentityResolver} leaves the tier-A
 * directory ({@code LocalIdentities}) in the context unused: it is a plain bean nobody asks for, which
 * is preferable to making the default unreplaceable to keep it tidy.
 */
@AutoConfiguration
public class KeelBaseIdentityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CallerAuthenticator.class)
    CallerAuthenticator delegationTokenAuthenticator(
            @Value("${keelbase.delegation.secret}") String secret,
            @Value("${keelbase.delegation.audience}") String audience) {
        return new DelegationTokenAuthenticator(secret, audience);
    }

    @Bean
    @ConditionalOnMissingBean(IdentityResolver.class)
    IdentityResolver securityIdentityResolver(LocalIdentities directory, Departments departments) {
        return new SecurityIdentityResolver(directory, departments);
    }
}
