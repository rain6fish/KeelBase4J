// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Assembles the runtime core for an application that is not this repository's (ADR-0017).
 *
 * <p>Everything the runtime is lives under {@code cn.com.keelbase.runtime}: the trust loop's engine
 * and its stores, the governance surface, the identity seam, and the security filter chain. Until this
 * class existed, the only thing that registered them was {@code @SpringBootApplication} on
 * {@code KeelBase4JApplication} — that class's component scan, whose root is the package they happen to
 * share. A host application scans its own packages and has never heard of ours, so it would start up
 * with the jar on its classpath and none of the beans in its context: endpoints that do not exist, and
 * no error to say why.
 *
 * <p>Two things follow, and both are deliberate:
 *
 * <p><b>Three scans, not one, and that is the part worth remembering.</b> Spring Boot derives all of
 * these from the package the <em>application</em> lives in: the components it finds, the entities its
 * persistence unit knows, and the Spring Data repositories it proxies. A host's package is not ours, so
 * each of the three comes up empty in turn — the third one is the quietest, because an interface with no
 * proxy produces no bean and no complaint until something asks for it. Component-scanning from a library
 * is worth being uneasy about, and this is the case where it is honest: {@code cn.com.keelbase.runtime}
 * belongs to this repository exclusively. It is also what keeps thirty-odd annotated classes from
 * having to be rewritten as bean methods purely to satisfy a packaging convention.
 *
 * <p><b>Scheduling is enabled here, not left to the host.</b> The confirmation offline window is only
 * meaningful with a periodic sweep behind it (see {@code ConfirmationSweeper}); an embedded core that
 * silently lost the sweep would keep confirmations decidable forever while looking installed. A host
 * that schedules its own work already is unaffected — {@code @EnableScheduling} is additive.
 *
 * <p>{@code @ConfigurationPropertiesScan} comes along for the same reason: the runtime's own properties
 * ({@code keelbase.*}) are part of what it is, and a host should not have to know they exist.
 *
 * <p>What is <em>not</em> here is as deliberate: no {@code application.properties}, no
 * {@code @SpringBootApplication}, no server or datasource opinion. Those belong to whoever deploys the
 * application — see ADR-0017 D4/D5.
 */
@AutoConfiguration
@ComponentScan("cn.com.keelbase.runtime")
@EntityScan("cn.com.keelbase.runtime")
@EnableJpaRepositories("cn.com.keelbase.runtime")
@ConfigurationPropertiesScan("cn.com.keelbase.runtime")
@EnableScheduling
public class KeelBaseRuntimeAutoConfiguration {
}
