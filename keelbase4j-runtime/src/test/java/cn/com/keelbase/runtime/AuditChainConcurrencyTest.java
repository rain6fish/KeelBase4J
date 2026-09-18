// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.runtime.audit.AuditService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JV-8 — the audit chain must stay linear when appends overlap.
 *
 * <p>What has to be proven is <b>cross-transaction</b> serialization, because that is what a second
 * instance contends for. A JVM monitor cannot provide it: it is released when the appending method
 * returns, while the transaction commits after that. Both cases below fail against a monitor alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuditChainConcurrencyTest {

    @Autowired
    AuditService audit;

    @Autowired
    PlatformTransactionManager transactions;

    /** One transaction holds the chain; another's append must not get through until it lets go. */
    @Test
    void anAppendInAnotherTransactionWaitsForTheOneHoldingTheChain() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();

        Thread holder = new Thread(() -> new TransactionTemplate(transactions).execute(status -> {
            audit.append("holder", "alice", "takes the chain");
            holding.countDown();
            await(release);
            return null;
        }), "chain-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue(holding.await(20, TimeUnit.SECONDS), "the holder should have taken the chain");

        Thread second = new Thread(() -> {
            try {
                audit.append("second", "bob", "must wait");
            } catch (Throwable failure) {
                failed.set(failure);
            } finally {
                secondDone.countDown();
            }
        }, "second-appender");
        second.setDaemon(true);
        second.start();

        // A window in which serialization that spanned only the method body would already be done.
        assertFalse(secondDone.await(500, TimeUnit.MILLISECONDS),
                "the second append must not run while another transaction holds the chain");

        release.countDown();
        assertTrue(secondDone.await(20, TimeUnit.SECONDS), "and must run once the lock is released");
        holder.join(20_000);
        assertNull(failed.get(), "neither append should fail");
        assertTrue(audit.verify().valid(), "and the chain stays valid");
    }

    /** Many appenders at once: no fork, and nothing dropped. */
    @Test
    void concurrentAppendsNeitherForkNorDropEntries() throws Exception {
        int threads = 8;
        int each = 12;
        int before = audit.verify().checked();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> appends = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                int id = t;
                appends.add(pool.submit(() -> {
                    await(start);
                    for (int i = 0; i < each; i++) {
                        audit.append("concurrent", "user-" + id, "append " + i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> append : appends) {
                append.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        AuditService.Verification verification = audit.verify();
        assertTrue(verification.valid(), "a forked chain would fail verification");
        assertEquals(before + threads * each, verification.checked(), "nothing may be lost");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
