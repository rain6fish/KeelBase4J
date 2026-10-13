// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.autoconfigure;

import cn.com.keelbase.runtime.audit.OperationAuditLogRepository;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.web.OperationAuditInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the operations audit — on by default, switchable off with
 * {@code keelbase.operations-audit.enabled=false}.
 *
 * <p><b>Why on by default.</b> The console's operations page is the reason it exists, and a page that
 * fills in only once somebody remembers to flip a property is a page that stays empty. The AI chain
 * beside it is always on for the same reason, and this is its sibling.
 *
 * <p><b>Why it is switchable.</b> This is a request interceptor, so it captures the writes of whatever
 * application embeds this runtime — including a host that already keeps an operations log of its own and
 * does not want a second one, and including a deployment for which the duplicate rows would be a data
 * question rather than a convenience.
 *
 * <p>**注册操作审计** —— 默认开，用 `keelbase.operations-audit.enabled=false` 关掉。
 *
 * <p><b>为什么默认开。</b> 控制台的操作审计页正是它存在的理由，而一个**要等人想起来翻开关**才会填的页，是一个
 * **一直空着**的页。旁边那条 AI 链出于同样的理由**一直开着**，这条是它的兄弟。
 *
 * <p><b>为什么可关。</b> 这是一个请求拦截器，所以它捕获的是**嵌入本运行时的那个应用**的写 —— 包括**自己已经有
 * 一份操作日志、不想要第二份**的宿主，也包括**多出这些行对它是数据问题、而不是方便**的部署。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "keelbase.operations-audit.enabled", havingValue = "true",
        matchIfMissing = true)
public class KeelBaseOperationsAuditAutoConfiguration implements WebMvcConfigurer {

    private final OperationAuditInterceptor interceptor;

    public KeelBaseOperationsAuditAutoConfiguration(OperationAuditLogRepository logs,
                                                    CurrentPrincipal principals) {
        this.interceptor = new OperationAuditInterceptor(logs, principals);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}
