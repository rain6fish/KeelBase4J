// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.pipeline;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the rule-based planner as a <em>default</em> rather than a fixture.
 *
 * <p>While {@link RuleBasedPlanner} was an unconditional {@code @Component}, the only way for a
 * deployment to supply its own {@link ToolCallPlanner} was to declare one <em>and</em> mark it
 * {@code @Primary} — the default had to be out-ranked. That is inside out: a runtime that ships a
 * fallback should step aside when a real one arrives.
 *
 * <p>Going through an auto-configuration is what buys the ordering. Auto-configurations are processed
 * after the application's own configuration, so {@link ConditionalOnMissingBean} sees a deployment's
 * planner and backs off. An adapter therefore needs one bean and nothing else — no {@code @Primary},
 * no exclusion list, no edit to this repository. That is what keeps the runtime from having to learn
 * about adapters, which is the one-way rule (CLAUDE.md 硬规则 6) and ADR-0004 D2/D3 made mechanical.
 *
 * <p>The bean is declared by type, so an absent bean and a bean of another type are the same case:
 * only a real {@link ToolCallPlanner} suppresses this one.
 */
@AutoConfiguration
@ConditionalOnMissingBean(ToolCallPlanner.class)
public class RuleBasedPlannerAutoConfiguration {

    @Bean
    ToolCallPlanner ruleBasedPlanner() {
        return new RuleBasedPlanner();
    }
}
