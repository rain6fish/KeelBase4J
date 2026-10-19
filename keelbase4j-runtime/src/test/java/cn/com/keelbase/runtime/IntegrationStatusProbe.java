// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.runtime.audit.AuditService;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Calls the integration-health surface the way the tests need it: an authenticated GET, the frozen
 * envelope unwrapped, and one block read out.
 *
 * <p>Kept in one place so each test asserts on the reading rather than on the transport. It mints a
 * token with the configured secret rather than inventing a second way in — the endpoint is
 * authenticated, and a test that bypassed that would stop proving the guard.
 *
 * 按测试需要调用接入健康度面：一次已认证的 GET、拆掉冻结信封、取出其中一个块。
 *
 * <p>集中在一处，好让每个测试断言**读数**而不是传输。它用配置的 secret 铸令牌，而不是另造一条进入方式
 * —— 端点是已认证的，绕过它的测试就不再证明那道守卫。
 */
final class IntegrationStatusProbe {

    static final String PATH = "/keelbase/status";

    private IntegrationStatusProbe() {
    }

    /** An authenticated GET. {@code secret} is the deployment's delegation secret. */
    static ResponseEntity<String> get(TestRestTemplate rest, String path, String user, String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, secret));
        return rest.exchange(URI.create(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** The {@code data} of a successful call, through the frozen {@code api-response} envelope. */
    static Map<String, Object> data(ResponseEntity<String> response) {
        return Envelopes.data(Json.parse(response.getBody()));
    }

    /** One named block of the report. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> block(Map<String, Object> report, String name) {
        return (Map<String, Object>) report.get(name);
    }

    /**
     * One audit row, so the audit dimension has something real to count. A unique caller keeps the
     * assertion about this run rather than about whatever else the isolated database holds.
     *
     * 一行审计，好让审计维度有真东西可数。唯一的调用方让断言针对本次运行，而不是隔离库里的其它东西。
     */
    static void seedAudit(AuditService audit) {
        audit.append("tool_call", "status-" + UUID.randomUUID(), "seed", false, "executed",
                "create_followup");
    }

    /** A numeric reading, whatever JSON number width the parser chose. */
    static int number(Map<String, Object> block, String key) {
        return ((Number) block.get(key)).intValue();
    }
}
