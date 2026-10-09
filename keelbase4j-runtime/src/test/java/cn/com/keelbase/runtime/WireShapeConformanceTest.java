// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.protocol.Vectors;
import cn.com.keelbase.protocol.WireSchemas;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The two shapes every response is made of, held against the frozen schemas.
 *
 * <p>These are the objects a conformance audit found unchecked: the envelope wraps <em>every</em>
 * successful response ({@code ApiResponseAdvice}) and the failure body covers every refusal, so a
 * drift in either is a drift everywhere — and until this test, nothing pinned either of them to the
 * contract. The rest of this module's assertions are structural, by design and for a reason stated in
 * {@code AppContractTest}; these two are the case where the schema is a single small shape and holding
 * it directly costs one call.
 *
 * <p><b>The check is the shared one</b> ({@link WireSchemas}, reached through this module's existing
 * test-jar dependency), so the meaning of "conforms" is the same here as in the protocol module's wire
 * tests rather than a second opinion that can drift.
 *
 * 每一条响应都由这两个形状构成，这里把它们拿去对冻结 schema。
 *
 * <p>它们正是一次符合性盘点查出来的、无人校验的对象：信封裹着**每一条成功响应**
 * （{@code ApiResponseAdvice}），失败体覆盖**每一次拒绝** —— 任一处漂移就是处处漂移，而在本测试之前，
 * **没有任何东西**把它们钉在契约上。本模块其余的断言是**结构**的，那是刻意的、理由写在
 * {@code AppContractTest} 里；而这两个是「schema 就一小块、直接对它只花一次调用」的那种情形。
 *
 * <p><b>用的是共享的那份检查</b>（{@link WireSchemas}，经本模块既有的 test-jar 依赖够到），所以
 * 「符合」在这里的含义与协议模块的 wire 测试**完全相同**，而不是第二份会各自漂移的意见。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
class WireShapeConformanceTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void aSuccessfulResponseCarriesTheFrozenEnvelope() {
        ResponseEntity<String> response = rest.getForEntity("/app/capabilities", String.class);

        assertEquals(200, response.getStatusCode().value(), "the probe endpoint must answer");
        Map<String, Object> body = Vectors.map(Json.parse(response.getBody()));
        WireSchemas.assertConformsTo(body, "api-response");
    }

    @Test
    void aRefusedRequestCarriesTheFrozenFailureBody() {
        // No token: the point is a refusal, and that a refusal is still contract-shaped. A bare
        // status with an empty or ad-hoc body is exactly what a frontend cannot act on.
        ResponseEntity<String> response = rest.getForEntity("/ai/my/confirmations", String.class);

        assertTrue(response.getStatusCode().is4xxClientError(),
                "the call is refused, not served: " + response.getStatusCode());
        assertTrue(response.getStatusCode().value() != 404,
                "the refusal must be authorisation, not a missing route");
        Map<String, Object> body = Vectors.map(Json.parse(response.getBody()));
        WireSchemas.assertConformsTo(body, "error-body");
    }
}
