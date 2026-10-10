// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.audit;

import cn.com.keelbase.protocol.AuditChain;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends to the AI audit hash chain, verifies it, and reads the rows back out.
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

    /**
     * Appends a row that records something going the way it was asked to.
     *
     * <p>Kept as the shorter call for the many sites where nothing went wrong; the ones that record a
     * refusal, a block, a decline or a failed execution use the {@link #append(String, String, String,
     * boolean)} overload and say so.
     *
     * 追加一行，记的是**按所求发生**的事。留给许多「没出事」的调用点；记拒绝、拦截、否决或执行失败的那些走
     * 下面那个重载、并把这一点说出来。
     */
    @Transactional
    public AuditLog append(String action, String userId, String detail) {
        return append(action, userId, detail, false);
    }

    /**
     * The same, recording whether this row is one of the calls that did not go the way the caller asked.
     *
     * <p>{@code isError} is stored beside the chain rather than inside it — see
     * {@code V6__audit_row_is_error} for why folding it into the hashed payload would report every
     * deployment's existing history as a broken chain.
     *
     * 同上，另外记下这一行**是否属于「没按调用方所求发生」的调用**。
     *
     * <p>{@code isError} 存在链**旁边**、不在链**里面** —— 把它折进哈希载荷会让每个部署**既有的历史**都被
     * 报成断链，理由见 `V6__audit_row_is_error`。
     */
    @Transactional
    public AuditLog append(String action, String userId, String detail, boolean isError) {
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
        return repository.save(new AuditLog(action, userId, detail, prevHash, hash, isError));
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
        return new Verification(v.valid(), v.checked(), v.brokenIndex(),
                chain(logs, v.brokenIndex()));
    }

    /**
     * The rows the verification walked, in the frozen {@code chain} shape.
     *
     * <p>What it carries is what this runtime knows about a row: its identity, when it was written, what
     * it recorded, whether that went the way the caller asked, and its two chain hashes. A row that is
     * not the one the walk broke on carries no {@code broken} flag rather than a {@code false} one —
     * "this row is not the break" is the absence of the claim, not a claim of its own.
     *
     * 校验走过的那些行，按冻结的 `chain` 形状。带上的是本运行时**对一行所知道的东西**：它是谁、什么时候写的、
     * 记了什么、那件事是否按调用方所求发生、以及它的两个链哈希。**不是断在哪里的那一行**不带 `broken` 标记 ——
     * 而不是带一个 `false` —— 「这一行不是断点」是**没有那个主张**，不是**有**一个主张。
     */
    /**
     * The window of the chain the answer carries. The frozen object calls {@code chain} a <em>window</em>
     * (E-2), and that is what the reference sends: a deployment with a million rows must not answer one
     * request with a million rows. A chain that holds shows its newest {@value #CHAIN_SLICE} rows; a
     * chain that broke shows a window around the break, with the row the walk stopped on marked.
     *
     * 这条答案携带的**链窗口**。冻结对象把 {@code chain} 称作**窗口**（E-2），而参照实现送的就是窗口：
     * 一个有**一百万行**的部署，不该用一个请求把它们**全**答出来。链站得住时给**最新**的
     * {@value #CHAIN_SLICE} 行；断了时给**断点周围**的一窗，并把走停下来的那一行标出来。
     */
    private static List<Map<String, Object>> chain(List<AuditLog> logs, Integer brokenIndex) {
        // The interface counts from one; the list is walked from zero.
        int broken = brokenIndex == null ? -1 : brokenIndex - 1;
        int from = broken < 0 ? Math.max(0, logs.size() - CHAIN_SLICE) : Math.max(0, broken - 6);
        int to = broken < 0 ? logs.size() : Math.min(logs.size(), broken + 4);
        List<Map<String, Object>> chain = new ArrayList<>();
        for (int i = from; i < to; i++) {
            AuditLog log = logs.get(i);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", log.getId());
            node.put("createdAt", log.getCreatedAt().toString());
            node.put("action", log.getAction());
            node.put("prevHash", log.getPrevHash());
            node.put("hash", log.getHash());
            node.put("isError", log.isError());
            if (i == broken) {
                node.put("broken", true);
            }
            chain.add(node);
        }
        return chain;
    }

    /**
     * How many rows a valid chain shows — the reference's own `CHAIN_SLICE`, so a third party reading
     * either runtime sees the same window.
     *
     * 一条完好的链显示多少行 —— 就是参照实现自己的 `CHAIN_SLICE`，故读**任一**运行时的人看到的是**同一个窗口**。
     */
    private static final int CHAIN_SLICE = 24;

    /**
     * The rows on the chain that match a filter, newest first, in the window asked for.
     *
     * <p>This is the read surface the chain did not have. {@link #append} writes rows and
     * {@link #verify} says whether the chain holds, but neither hands the rows back, so "which rows
     * did this caller put on the chain in this period" had no answer at all — the repository that
     * holds them was reachable only from inside this package. What comes back is what the frozen
     * {@code ai-audit-log-row} asks for and this runtime knows: which row, who acted, what was
     * recorded, whether it went the way the caller asked, and when.
     *
     * <p><b>Fields the object declares but this runtime does not record are absent, not null.</b>
     * Conversations, agent and delegation identity, token counts, feedback, an authorisation
     * verdict — this runtime has no such concepts, and an absent field says exactly that, while a
     * null one would claim the row could have carried a value.
     *
     * <p>The window is expressed the way the reference expresses it — a count to skip and a count to
     * take — and a {@code PageRequest} can only skip a multiple of the page size, so the span is
     * fetched and the leading rows dropped here. The caller caps {@code offset}, which is what keeps
     * that fetch bounded.
     *
     * 链上符合筛选的那些行，最新的在前，落在所要的窗口里。
     *
     * <p>这是链**此前没有的读面**。{@link #append} 写行、{@link #verify} 说链站不站得住，但两者都**不把行交回来**，
     * 于是「这个调用方在这一段时间里往链上留了哪几行」**根本没有答案** —— 存着它们的仓库此前只有本包内够得到。
     * 回出来的是冻结的 {@code ai-audit-log-row} 所要、而本运行时**知道**的东西：哪一行、谁动的手、记了什么、
     * 那件事是否按调用方所求发生、以及什么时候。
     *
     * <p><b>对象声明了、而本运行时不记的字段，是**缺席**、不是 null。</b>会话、agent 与委托身份、token 计数、
     * 反馈、授权结论 —— 本运行时**没有这些概念**；**缺席**正是这么说的，而 null 会**主张**这一行本可以带一个值。
     *
     * <p>窗口按参照实现的说法表达 —— 跳过几条、取几条 —— 而 {@code PageRequest} 只能跳**页大小的整数倍**，
     * 故这里把整段取回来、再把前面几行丢掉。**调用方封顶 {@code offset}**，那正是这次取数有界的原因。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> rows(String userId, Instant since, Boolean isError,
                                          int limit, int offset) {
        Specification<AuditLog> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (since != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), since));
            }
            if (isError != null) {
                predicates.add(cb.equal(root.get("error"), isError));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Pageable span = PageRequest.of(0, limit + offset, Sort.by(Sort.Direction.DESC, "id"));
        return repository.findAll(filter, span).stream()
                .skip(offset)
                .limit(limit)
                .map(AuditService::row)
                .toList();
    }

    /**
     * One row, in the frozen {@code ai-audit-log-row} shape — the six fields this runtime has, and
     * nothing beyond them ({@code additionalProperties} is false, so a guess would be a violation
     * rather than a courtesy).
     *
     * 一行，按冻结的 {@code ai-audit-log-row} 形状 —— 本运行时**有的那六个字段**，此外什么都没有
     * （{@code additionalProperties} 是 false，故**猜一个**是违规、不是好意）。
     */
    private static Map<String, Object> row(AuditLog log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", log.getId());
        m.put("userId", log.getUserId());
        m.put("action", log.getAction());
        m.put("detail", log.getDetail());
        m.put("isError", log.isError());
        m.put("createdAt", log.getCreatedAt().toString());
        return m;
    }

    /**
     * The verification's answer. {@code chain} is the frozen object's own field and the reason that
     * route exists at all: the walk says whether the chain holds, and this says what it walked.
     *
     * 校验的答案。{@code chain} 是冻结对象自己的字段，也是那条路由存在的原因：这次走**说链站不站得住**，
     * 而它**说走过了什么**。
     */
    public record Verification(boolean valid, int checked, Integer brokenIndex,
                               List<Map<String, Object>> chain) {
    }
}
