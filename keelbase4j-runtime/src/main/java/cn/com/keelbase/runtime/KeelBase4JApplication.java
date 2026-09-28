// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import cn.com.keelbase.runtime.autoconfigure.KeelBaseRuntimeAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * KeelBase4J runtime (Phase-0 spike).
 *
 * <p>A minimal Spring Boot application whose AI operations are executed only inside the KeelBase
 * trust boundary — Identity → Permission → Governance → Confirmation → Audit → Revoke. The point
 * of G1 is that the boundary is enforced by the runtime, not by prompt discipline.
 *
 * <p>{@code UserDetailsServiceAutoConfiguration} is excluded on purpose: it would conjure a single
 * in-memory user and a generated password, which is not how this runtime authenticates anyone.
 * Callers prove who they are with a delegation token, and a local directory maps that subject to a
 * user and role (see {@code runtime.security}).
 *
 * <p>Scheduling is enabled because the confirmation offline window is only meaningful with a
 * periodic sweep behind it — see {@code runtime.governance.ConfirmationSweeper} for why a
 * confirmation would otherwise stay decidable forever.
 *
 * <p><b>The core's auto-configuration is excluded here, and that is not a contradiction.</b> This
 * application's own component scan covers the core already — its package <em>is</em> the core's — so
 * letting the auto-configuration assemble the same beans a second time registers two definitions for
 * one repository interface and the transactional proxy silently becomes the one you did not intend
 * (ADR-0017 D3). An application that scans the core opts out of the auto-configuration; an application
 * that has never heard of the core, which is what a host is, is the case the auto-configuration exists
 * for. {@code CoreAssemblyTest} is where that second case is exercised.
 */
@SpringBootApplication(exclude = {
        UserDetailsServiceAutoConfiguration.class,
        KeelBaseRuntimeAutoConfiguration.class
})
@ConfigurationPropertiesScan
@EnableScheduling
public class KeelBase4JApplication {

    public static void main(String[] args) {
        SpringApplication.run(KeelBase4JApplication.class, args);
    }
}
