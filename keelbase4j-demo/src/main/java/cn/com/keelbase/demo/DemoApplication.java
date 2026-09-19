// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.demo;

import cn.com.keelbase.runtime.KeelBase4JApplication;
import org.springframework.boot.SpringApplication;

/**
 * The runtime, with a model attached.
 *
 * <p>It runs {@link KeelBase4JApplication} rather than declaring an application of its own, and that
 * is deliberate: this demo is not a different runtime with extra wiring, it is <em>the</em> runtime
 * — same beans, same configuration, same trust loop — with two extra jars on the classpath. The
 * Spring AI adapter finds the seam and the provider gives it a {@code ChatClient}; the runtime's own
 * planner stands down. Nothing here participates in that, which is the property being demonstrated.
 *
 * <p>Configuration is supplied by the environment (see {@code scripts/demo-springai.sh}). This module
 * deliberately ships no {@code application.properties}: a file at the classpath root would shadow the
 * runtime's own, and quietly take the database and delegation settings with it.
 */
public final class DemoApplication {

    private DemoApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.run(KeelBase4JApplication.class, args);
    }
}
