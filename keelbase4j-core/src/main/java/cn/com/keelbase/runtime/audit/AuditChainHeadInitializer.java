// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Makes sure the chain's lock row exists before anything appends to the chain.
 *
 * <p>Two instances starting at once can race here. The loser's insert violates the primary key and is
 * caught: the row existing is all that matters, because the lock is what it is for. The attempt is
 * deliberately not inside a shared transaction, so a lost race rolls back on its own and leaves the
 * append path untouched.
 */
@Component
public class AuditChainHeadInitializer implements ApplicationRunner {

    private final AuditChainHeadRepository heads;

    public AuditChainHeadInitializer(AuditChainHeadRepository heads) {
        this.heads = heads;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (heads.existsById(AuditChainHead.SINGLETON)) {
            return;
        }
        try {
            heads.saveAndFlush(new AuditChainHead(AuditChainHead.SINGLETON));
        } catch (DataIntegrityViolationException lostTheRace) {
            // Another instance created it first. Nothing to do — the row is there.
        }
    }
}
