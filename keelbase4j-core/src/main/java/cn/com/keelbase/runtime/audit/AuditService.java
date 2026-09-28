// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import cn.com.keelbase.protocol.AuditChain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends to the AI audit hash chain and verifies it.
 *
 * <p>The chain algorithm is the frozen protocol one (reused from G0). Appends are serialized on the
 * chain's lock row, not by a JVM monitor: a monitor is released when the appending method returns
 * while the transaction commits <em>after</em> that, so a second appender can read the same head in
 * that gap and fork the chain — a window that is open even in a single process. The row lock is held
 * until the transaction ends, and it is the same lock a second instance would contend for on a shared
 * database, which a monitor never could be.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final AuditChainHeadRepository heads;
    private final String chainKey;

    public AuditService(AuditLogRepository repository, AuditChainHeadRepository heads,
                        @Value("${keelbase.audit.hmac-key}") String chainKey) {
        this.repository = repository;
        this.heads = heads;
        this.chainKey = chainKey;
    }

    @Transactional
    public AuditLog append(String action, String userId, String detail) {
        // Serialize here for the rest of the transaction: reading the head hash, computing the next
        // one and inserting it all happen while no other appender can be between those same steps.
        if (heads.lockById(AuditChainHead.SINGLETON).isEmpty()) {
            throw new IllegalStateException(
                    "the audit chain head row is missing, so appends cannot be serialized; "
                            + "AuditChainHeadInitializer creates it at startup");
        }
        String prevHash = repository.findTopByOrderByIdDesc().map(AuditLog::getHash).orElse(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("userId", userId);
        payload.put("detail", detail);
        String hash = AuditChain.hash(chainKey, prevHash, payload);
        return repository.save(new AuditLog(action, userId, detail, prevHash, hash));
    }

    /** Walk the chain and recompute every hash. */
    @Transactional(readOnly = true)
    public Verification verify() {
        List<AuditLog> logs = repository.findAllByOrderByIdAsc();
        List<AuditChain.ChainRow> rows = new ArrayList<>();
        Map<Long, Object> payloads = new LinkedHashMap<>();
        for (AuditLog log : logs) {
            rows.add(new AuditChain.ChainRow(log.getId().intValue(), log.getPrevHash(), log.getHash()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", log.getAction());
            payload.put("userId", log.getUserId());
            payload.put("detail", log.getDetail());
            payloads.put(log.getId(), payload);
        }
        AuditChain.Verification v =
                AuditChain.verify(rows, List.of(chainKey), row -> payloads.get((long) row.id()));
        return new Verification(v.valid(), v.checked(), v.brokenIndex());
    }

    public record Verification(boolean valid, int checked, Integer brokenIndex) {
    }
}
