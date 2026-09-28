// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * An application that is <em>not</em> this repository's, for the assembly test to boot.
 *
 * <p>Its package is deliberately outside {@code cn.com.keelbase.runtime}: this is what a host looks
 * like — it scans its own classes and has never heard of the core's. If the core assembled itself only
 * through a component scan of that package, a context built from this class would come up with none of
 * the runtime's beans, and the test that boots it would say so.
 */
@SpringBootApplication
public class CoreTestApplication {
}
