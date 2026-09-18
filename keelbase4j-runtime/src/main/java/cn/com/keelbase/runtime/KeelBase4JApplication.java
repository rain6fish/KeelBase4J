// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

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
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class KeelBase4JApplication {

    public static void main(String[] args) {
        SpringApplication.run(KeelBase4JApplication.class, args);
    }
}
