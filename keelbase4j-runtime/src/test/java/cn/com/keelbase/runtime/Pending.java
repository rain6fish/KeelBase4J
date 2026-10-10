// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import cn.com.keelbase.protocol.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * The confirmation a caller is holding, read the way a caller reads it.
 *
 * <p>It exists because the non-streaming chat response no longer carries the token: the frozen
 * {@code chat-response} has no field for one, and the protocol's prose says so outright. A caller
 * proposing a write over that path therefore learns about it from their own confirmation centre —
 * which is the surface the product gives them, and now the one these tests read too. Reading it there
 * is not a workaround for a lost assertion: "a pending row exists for this caller" is what the gate's
 * having held actually means, and it is what the old assertion on the response body stood for.
 *
 * 调用方手上那次确认，按**调用方读它的方式**读。
 *
 * <p>它存在，是因为非流式聊天响应**不再带 token**：冻结的 {@code chat-response} 没有放它的字段，协议散文也
 * 明说了这一点。于是从那条路径提出一次写的调用方，是**从自己的确认中心**知道这件事的 —— 那是产品给他们的面，
 * 现在也是这些测试读的那个面。在那里读**不是**丢了断言之后的将就：「**这个调用方有一行待确认**」正是**闸门真的
 * 拦住了**的含义，也正是旧断言看响应体时所要证的东西。
 */
final class Pending {

    private Pending() {
    }

    /** How many confirmations the caller is holding, asked over {@link TestRestTemplate}. */
    static int count(TestRestTemplate rest, String user, String delegationSecret) {
        return items(rest, user, delegationSecret).size();
    }

    /** The same, for the classes that drive the API with {@code java.net.http}. */
    static int count(int port, String user, String delegationSecret) throws Exception {
        return items(port, user, delegationSecret).size();
    }

    /** The caller's newest pending confirmation's token, asked over {@link TestRestTemplate}. */
    static String token(TestRestTemplate rest, String user, String delegationSecret) {
        return newest(items(rest, user, delegationSecret), user);
    }

    /** The same, for the classes that drive the API with {@code java.net.http}. */
    static String token(int port, String user, String delegationSecret) throws Exception {
        return newest(items(port, user, delegationSecret), user);
    }

    private static List<?> items(TestRestTemplate rest, String user, String delegationSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(user, delegationSecret));
        Map<?, ?> body = rest.exchange("/ai/my/confirmations?status=pending", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class).getBody();
        return (List<?>) Envelopes.data(body);
    }

    private static List<?> items(int port, String user, String delegationSecret) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/ai/my/confirmations?status=pending"))
                .header("Authorization", "Bearer " + TestTokens.forUser(user, delegationSecret))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        return (List<?>) Envelopes.data(Json.parse(response.body()));
    }

    /**
     * Newest first, by the item's own {@code createdAt} — the server's listing order is not this test's
     * subject, and a proposal made a moment ago is the one the caller is waiting on.
     */
    private static String newest(Object data, String user) {
        String newestToken = null;
        String newestAt = null;
        for (Object raw : (List<?>) data) {
            Map<?, ?> item = (Map<?, ?>) raw;
            String at = String.valueOf(item.get("createdAt"));
            if (newestAt == null || at.compareTo(newestAt) > 0) {
                newestAt = at;
                newestToken = String.valueOf(item.get("token"));
            }
        }
        if (newestToken == null) {
            throw new AssertionError(
                    user + " holds no pending confirmation, so the write was not gated");
        }
        return newestToken;
    }
}
