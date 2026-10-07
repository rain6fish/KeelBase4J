// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest.host;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A controller shaped like a host's: it lives in the host's own package, and its consumer is that host's
 * own frontend rather than this runtime's wire contract.
 *
 * <p>It exists so the envelope's exclusion can be asserted against a package that is neither this
 * runtime's nor its error surface — the case a host actually brings.
 *
 * 一个**宿主形状**的控制器：住在宿主自己的包里，消费者是**那个宿主自己的**前端，不是本运行时的 wire 契约。
 *
 * <p>它存在，是为了让信封的**排除**能对着一个**既不是本运行时、也不是它的错误面**的包来断言——正是宿主真正带来的那种。
 */
@RestController
public class HostShapedController {

    @GetMapping("/host-shaped")
    Map<String, Object> hostShaped() {
        return Map.of("host", "this controller answers in the host's own shape");
    }
}
