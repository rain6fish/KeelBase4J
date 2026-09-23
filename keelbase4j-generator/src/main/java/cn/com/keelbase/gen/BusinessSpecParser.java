// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.gen;

import cn.com.keelbase.gen.BusinessSpec.EntitySpec;
import cn.com.keelbase.gen.BusinessSpec.FieldSpec;
import cn.com.keelbase.gen.BusinessSpec.PolicyRule;
import cn.com.keelbase.gen.BusinessSpec.RoleRule;
import cn.com.keelbase.gen.BusinessSpec.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * S1 — turns a natural-language business request into a {@link BusinessSpec}, and applies a
 * subsequent change request to an existing spec.
 *
 * <p>Spike: a deterministic router over two fixed sentences. The semantic contract — "a business
 * description becomes an explicit, reviewable spec; a change becomes an explicit spec change" — is
 * what matters; a model-backed parser replaces this later without changing anything downstream.
 */
@Component
public class BusinessSpecParser {

    /** The initial request → the base spec. */
    public BusinessSpec parse(String request) {
        String text = request == null ? "" : request;
        require(text, "客户", "客户 entity");
        require(text, "跟进", "follow-up entity");
        require(text, "销售", "the sales role");
        require(text, "经理", "the manager role");
        require(text, "风险", "the risk-analysis tool");
        require(text, "确认", "the confirmation requirement");

        List<EntitySpec> entities = List.of(
                new EntitySpec("Customer", List.of(
                        new FieldSpec("name", "string", "客户名称"),
                        new FieldSpec("level", "string", "客户等级"))),
                new EntitySpec("FollowUp", List.of(
                        new FieldSpec("customerId", "int", "客户"),
                        new FieldSpec("note", "string", "跟进内容"),
                        new FieldSpec("dueDate", "date", "提醒日期"))));

        RoleRule roleRule = new RoleRule("ownerUserId", "user", "销售仅见本人客户；经理可见全部");

        List<ToolSpec> tools = List.of(
                new ToolSpec("analyze_customer_risk", "R1", false, null, "分析客户风险（只读，自动执行）"),
                new ToolSpec("create_followup", "R3", true, "follow_up", "创建客户跟进（业务敏感写，需人工确认）"));

        // The module's name as the request gives it ("客户管理系统") — the capability surface reports a
        // module label, and that label is the module's, not the first entity's.
        return new BusinessSpec("crm", "客户管理", entities, roleRule, tools, List.of());
    }

    /**
     * Apply a change request to an existing spec. The change is explicit and incremental — it edits
     * the spec, it does not restate the whole business.
     */
    public BusinessSpec applyChange(BusinessSpec base, String changeRequest) {
        String text = changeRequest == null ? "" : changeRequest;
        List<EntitySpec> entities = new ArrayList<>(base.entities());
        List<PolicyRule> policies = new ArrayList<>(base.policies());

        boolean addField = text.contains("增加") && (text.contains("等级") || text.contains("tier"));
        if (addField) {
            for (int i = 0; i < entities.size(); i++) {
                EntitySpec entity = entities.get(i);
                if (!entity.name().equals("Customer")) {
                    continue;
                }
                List<FieldSpec> fields = new ArrayList<>(entity.fields());
                if (fields.stream().noneMatch(f -> f.name().equals("tier"))) {
                    fields.add(new FieldSpec("tier", "string", "客户等级"));
                }
                entities.set(i, new EntitySpec(entity.name(), fields));
            }
        }

        if (text.contains("只有经理") || text.contains("仅经理")) {
            if (policies.stream().noneMatch(p -> p.entity().equals("Customer") && p.action().equals("update"))) {
                policies.add(new PolicyRule("Customer", "update", "manager"));
            }
        }

        return new BusinessSpec(base.module(), base.moduleLabel(), entities, base.roleRule(), base.tools(),
                policies);
    }

    private static void require(String text, String needle, String what) {
        if (!text.contains(needle)) {
            throw new IllegalArgumentException("business request does not mention " + what
                    + " (expected \"" + needle + "\")");
        }
    }
}
