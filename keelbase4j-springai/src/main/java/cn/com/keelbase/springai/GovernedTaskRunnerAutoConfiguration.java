// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.springai;

import cn.com.keelbase.runtime.engine.GovernedExecutionEngine;
import cn.com.keelbase.runtime.pipeline.TaskRunner;
import cn.com.keelbase.runtime.tool.ToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The multi-step path, registered on its own rather than beside the planner.
 *
 * <p>That separation is deliberate: {@link SpringAiPlannerAutoConfiguration} is conditional on no
 * other {@code ToolCallPlanner} existing, because exactly one planner may be a bean. Hanging the
 * runner off it would mean a host that supplies its own planner silently loses the runner too —
 * a different thing going missing because of an unrelated choice.
 *
 * <p>多步路径**单独注册**，不和规划器挤在一起。
 *
 * <p>这条分离是刻意的：{@link SpringAiPlannerAutoConfiguration} 的条件是**没有别的**
 * {@code ToolCallPlanner}（规划器只能有一个 bean）。把 runner 挂在它下面，会让**自带规划器的宿主连 runner 也
 * 一起丢掉**——一件不相干的选择导致另一件东西消失。
 */
@AutoConfiguration
@AutoConfigureAfter(ChatClientAutoConfiguration.class)
public class GovernedTaskRunnerAutoConfiguration {

    /**
     * The conditions sit on the method rather than on the class, and that is the whole point of the
     * shape: on an auto-configuration class they are evaluated before the beans other configurations
     * contribute have been registered, so a chat client that arrives from one of them is not visible
     * yet and the runner is silently skipped. On the method they are evaluated late enough to see it —
     * the same lesson this repository already paid for once, on the other half of this seam.
     *
     * <p>It backs off for <b>any</b> implementation of the seam, not merely for one of its own type.
     * The planner's configuration does the same, and for the same reason: the route resolves the seam
     * rather than this class, so a deployment that declares a runner of its own has to <em>replace</em>
     * this one. Left beside it, {@code /ai/task} would find two beans and answer with a 500 that says
     * nothing — the one outcome that route exists not to produce.
     *
     * 条件放在**方法**上而不是**类**上，这个形状本身就是要点：挂在自动配置类上时，它们的求值**早于**别的
     * 配置贡献的 bean 被注册，于是从那里来的 chat client 此刻还看不见，runner 就被**静默跳过**。放在方法上
     * 则求值得够晚、看得见它——这条教训本仓已经在这条缝的另一半上付过一次学费。
     *
     * <p>它让位的对象是这条缝的**任何**实现，而不只是自己这个类型。规划器的配置也是这么做的，理由相同：那条路由
     * 解析的是**接缝**、不是本类，所以自带 runner 的部署必须**替换**掉这一个。留在旁边的话，`/ai/task` 会看见
     * **两个 bean**，然后答一个**什么也说不出的 500** —— 那正是这条路由存在的意义所排除的结果。
     */
    @Bean
    @ConditionalOnBean({ChatClient.Builder.class, GovernedExecutionEngine.class})
    @ConditionalOnMissingBean(TaskRunner.class)
    GovernedTaskRunner governedTaskRunner(ChatClient.Builder builder, ToolRegistry tools,
                                          GovernedExecutionEngine engine) {
        return new GovernedTaskRunner(builder.build(), tools, engine::execute);
    }
}
