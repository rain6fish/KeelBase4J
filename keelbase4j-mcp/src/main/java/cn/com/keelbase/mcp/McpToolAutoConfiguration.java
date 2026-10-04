// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.mcp;

import cn.com.keelbase.runtime.tool.AiTool;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Wires a configured MCP server in, and does nothing at all when none is configured.
 *
 * <p><b>Each exposed tool becomes a bean of its own.</b> That is not a stylistic choice: the runtime's
 * registry is built from every {@code AiTool} bean in the context, so a bean is the whole of what
 * "this tool is governed like the others" means. Registering one object that happens to hold a list
 * would leave those tools outside the chain that authorization, confirmation and audit come from.
 *
 * <p><b>One session, opened before the definitions that need it.</b> The server is asked once, its
 * answer decides what is registered, and the same session — itself registered as a bean, closed with
 * the context — is what those tools call through. A second connection would ask the same server the
 * same question twice and hold two sockets to it.
 *
 * <p>Discovery therefore happens <b>once, at startup</b>, and a server that is unreachable then is a
 * startup failure rather than a host that quietly has fewer tools than its operator believes it has. A
 * server whose tool set changes needs a restart; watching one live is a decision nobody has asked for.
 *
 * 把一台配置好的 MCP 服务端接进来；没配就什么都不做。
 *
 * <p><b>每一个被暴露的工具都成为它自己的一个 bean。</b>这不是风格选择：运行时的注册表由上下文里每一个
 * {@code AiTool} bean 构成，所以「这个工具与别的工具受同一种治理」的全部含义就是一个 bean。注册一个「恰好持有
 * 列表」的对象，会让这些工具落在授权、确认、审计所来自的那条链之外。
 *
 * <p><b>一条会话，在需要它的那些定义之前打开。</b>服务端只被问一次，它的回答决定注册什么，而**同一条会话**——
 * 它自己也注册成 bean、随上下文关闭——就是那些工具调用时走的通道。第二条连接会把同一个问题问第二遍，并对同一
 * 台服务端握住两个 socket。
 *
 * <p>因此发现只发生一次、在启动时；那时够不到的服务端是启动失败，而不是一个「工具比运维以为的少」却一声不响的
 * 宿主。工具集变化的服务端需要重启；实时盯着它是没人要求过的决定。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "keelbase.mcp", name = "server-url")
@EnableConfigurationProperties(McpProperties.class)
public class McpToolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpToolAutoConfiguration.class);

    @Bean
    McpToolPolicy mcpToolPolicy(McpProperties properties) {
        return properties.policy();
    }

    /**
     * Opens the session, lists what the server advertises, and registers the declared half.
     *
     * <p>A factory post-processor rather than a {@code @Bean} method because beans have to be
     * <em>defined</em> before the registry that consumes them is built, and the definitions are not
     * known until the server has answered.
     *
     * <p><b>The settings are bound here rather than injected.</b> A post-processor runs before the
     * post-processor that binds {@code @ConfigurationProperties}, so an injected properties bean would
     * arrive unbound — measured: {@code baseUri must not be empty}, because the URL was still null when
     * the client was built. Binding from the {@code Environment} directly is what makes this independent
     * of that order.
     *
     * 用工厂后处理器而不是 {@code @Bean} 方法：消费它们的那个注册表建立之前，bean 就必须已被定义，而定义
     * 要等服务端答完才知道。
     *
     * <p><b>配置在这里现绑，而不是注入。</b>后处理器跑在「绑定 {@code @ConfigurationProperties}」那个后处理器
     * **之前**，所以注入进来的配置 bean 会是**未绑定**的——实测：`baseUri must not be empty`，因为造客户端时那个
     * URL 还是 null。直接从 {@code Environment} 绑，才使它**与那次次序无关**。
     */
    @Bean
    static BeanFactoryPostProcessor mcpTools(Environment environment) {
        return new BeanFactoryPostProcessor() {
            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
                    throws BeansException {
                McpProperties properties =
                        Binder.get(environment).bind("keelbase.mcp", McpProperties.class)
                                .orElseGet(McpProperties::new);
                McpSyncClient session = McpServerTools.connect(properties.getServerUrl());
                McpServerTools.Discovery found = McpServerTools.discover(session, properties.policy());
                BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;

                // Declared by the interface, not by the implementation class: the declared type is what
                // by-type injection matches on, and every consumer here — the runtime's registry among
                // them — asks for the interface.
                //
                // 按**接口**而不是实现类声明：按类型注入匹配的是**声明的类型**，而这里每个消费方——包括运行时
                // 那个注册表——要的都是接口。
                RootBeanDefinition client = new RootBeanDefinition(McpSyncClient.class, () -> session);
                client.setDestroyMethodName("close");
                registry.registerBeanDefinition("mcpSyncClient", client);

                for (var tool : found.exposed()) {
                    // The instance is the definition: discovery already produced it, and building it
                    // again would open a second session against the same server.
                    registry.registerBeanDefinition(tool.name(),
                            new RootBeanDefinition(AiTool.class, () -> tool));
                }

                log.info("keelbase mcp {}: exposed {}, withheld for having no declaration {}",
                        properties.getServer(), found.exposed().size(), found.withheld().size());
                if (!found.withheld().isEmpty()) {
                    // Named, because the alternative is an operator wondering why a tool the server
                    // shows is missing from the catalogue.
                    log.warn("keelbase mcp {}: advertised but not declared, so not exposed: {}",
                            properties.getServer(), found.withheld());
                }
            }
        };
    }
}
