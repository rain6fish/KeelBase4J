// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.com.keelbase.runtime.web.ChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The chat request's slot is generic now, and the name it used to carry still binds.
 *
 * <p><b>Why this is a test rather than a comment.</b> The callers are not all in this repository: the
 * main repository's admin console posts {@code customerId} today. A rename that quietly stopped
 * binding that name would be a wire change dressed up as a tidy-up — the kind that surfaces in
 * production as a lost row id rather than in a build as a red line. It lives here rather than beside
 * the record because binder is what it needs, and this module is where one is on the classpath.
 *
 * 聊天请求那个槽位现在是通用的，而它**从前那个名字照样绑得上**。
 *
 * <p><b>为什么这是一条测试、不是一句注释。</b>调用方**不全在本仓**：主仓的管理界台今天就发 {@code customerId}。
 * 一次悄悄不再绑那个名字的改名，是把一次 wire 改动装扮成一次整理 —— 那种事在**生产里**表现为丢了一个行 id，
 * 而不是在**构建里**变成一行红。它住在这里、而不是挨着那条 record，是因为它要的是一个绑定器，而本模块的
 * classpath 上就有。
 */
class ChatRequestKeepsTheOldNameBindingTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theOldNameStillBinds() throws Exception {
        ChatRequest old = json.readValue("{\"message\":\"m\",\"customerId\":7}", ChatRequest.class);
        assertEquals(7L, old.targetId(), "the old key fills the generic slot");
        assertEquals(7L, old.customerId(), "and the deprecated accessor still answers");
    }

    @Test
    void theNewNameBinds() throws Exception {
        ChatRequest now = json.readValue("{\"message\":\"m\",\"targetId\":9}", ChatRequest.class);
        assertEquals(9L, now.targetId());
    }

    @Test
    void namingNeitherMeansItIsAbsent() throws Exception {
        ChatRequest none = json.readValue("{\"message\":\"m\"}", ChatRequest.class);
        assertNull(none.targetId(), "absent is absent, not zero");
    }
}
