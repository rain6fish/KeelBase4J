// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.engine.ExecutionOutcome;
import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.identity.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * A confirmation token is one-shot, and "one-shot" has to survive two people (or one impatient one
 * with two tabs) deciding at the same instant.
 *
 * <p>Both frozen sources say the same thing and neither is ambiguous about it:
 * {@code failure-semantics-v1} records the invariant as "token 一次性，**不二次执行**", and the
 * confirmation lifecycle's note puts it as "the conditional update is the only arbitration point for
 * either window, **which is why a concurrent or repeated decision cannot execute a tool twice**".
 *
 * <p>So the assertion is not about which call wins or what the loser is told — it is that the tool
 * ran <b>once</b>. This test was written before the fix and failed against it: two threads approving
 * the same token both executed, and two follow-ups were written.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfirmationConcurrencyTest {

    @Autowired
    GovernedExecutionEngine engine;

    @Autowired
    CustomerRepository customers;

    @Autowired
    FollowUpRepository followUps;

    @Test
    void aTokenApprovedTwiceAtOnceExecutesOnce() throws Exception {
        Long customerId = customers.save(new Customer("Race Co", "low", "alice")).getId();
        Principal alice = new Principal("alice", "user");
        ExecutionOutcome pending = engine.execute("create_followup",
                Map.of("customerId", customerId, "note", "race"), alice);
        assertEquals("pending_confirmation", pending.status());

        int racers = 2;
        CyclicBarrier bothReady = new CyclicBarrier(racers);
        List<String> executed = Collections.synchronizedList(new ArrayList<>());
        List<String> refused = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            Thread t = new Thread(() -> {
                try {
                    bothReady.await();
                    executed.add(engine.approve(pending.token(), alice).status());
                } catch (ResponseStatusException e) {
                    refused.add(String.valueOf(e.getStatusCode()));
                } catch (Exception e) {
                    refused.add(e.getClass().getSimpleName());
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(1, executed.size(), "exactly one approval may win; the other is refused");
        assertEquals(HttpStatus.CONFLICT.toString(), refused.get(0), "and refusing is what it is told");
        assertEquals(1, followUps.findByCustomerIdAndDeletedAtIsNull(customerId).size(),
                "the tool must not execute twice — that is the invariant both sources state");
    }
}
