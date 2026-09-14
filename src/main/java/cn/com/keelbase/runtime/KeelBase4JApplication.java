// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * KeelBase4J runtime (Phase-0 spike).
 *
 * <p>A minimal Spring Boot application whose AI operations are executed only inside the KeelBase
 * trust boundary — Identity → Permission → Governance → Confirmation → Audit → Revoke. The point
 * of G1 is that the boundary is enforced by the runtime, not by prompt discipline.
 */
@SpringBootApplication
public class KeelBase4JApplication {

    public static void main(String[] args) {
        SpringApplication.run(KeelBase4JApplication.class, args);
    }
}
