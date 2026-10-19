// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.domain.PmProject;
import cn.com.keelbase.runtime.domain.PmProjectRepository;
import cn.com.keelbase.runtime.domain.PmTask;
import cn.com.keelbase.runtime.domain.PmTaskRepository;
import java.time.LocalDate;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds the demo data both reference modules need, one row set per user. Not active under tests.
 *
 * <p>The PM seed plants a reading rather than decoration: one overdue task and one due soon under a
 * high-risk project make the deadline-risk analysis answer a specific level, so the demo can assert
 * it instead of printing whatever today produces.
 *
 * <p>为两个参考模块各灌一份演示数据，每个用户一份。测试下不生效。
 *
 * <p>PM 的种子种下的是一个**读数**、不是装饰：高风险项目下一条逾期、一条即将到期，使延期风险分析给出
 * 一个确定的等级，于是演示可以断言它，而不是打印今天恰好产出的东西。
 */
@Component
@Profile("!test")
public class SeedData implements CommandLineRunner {

    private final CustomerRepository customers;
    private final PmProjectRepository projects;
    private final PmTaskRepository tasks;

    public SeedData(CustomerRepository customers, PmProjectRepository projects,
                    PmTaskRepository tasks) {
        this.customers = customers;
        this.projects = projects;
        this.tasks = tasks;
    }

    @Override
    public void run(String... args) {
        seedCustomers();
        seedProjects();
    }

    private void seedCustomers() {
        if (customers.count() > 0) {
            return;
        }
        // Stamped with the organization and department they belong to, the way a create path would:
        // a row without them is reachable by its owner only, which is the tightening direction.
        Customer industrial = new Customer("Acme Industrial", "high", "alice");
        industrial.assign(1L, 11L);
        customers.save(industrial);

        Customer trading = new Customer("Globex Trading", "medium", "bob");
        trading.assign(1L, 12L);
        customers.save(trading);
    }

    private void seedProjects() {
        if (projects.count() > 0) {
            return;
        }
        LocalDate today = LocalDate.now();

        PmProject atlas = new PmProject("Atlas Launch", "active", "high",
                "Launch the customer portal", "alice");
        atlas.assign(1L, 11L);
        projects.save(atlas);
        task(atlas, "Prepare launch checklist", today.minusDays(2), "open");
        task(atlas, "Vendor sign-off", today.plusDays(3), "open");
        task(atlas, "Kickoff notes", today.minusDays(10), "done");

        PmProject orion = new PmProject("Orion Migration", "active", "medium",
                "Migrate the reporting jobs", "bob");
        orion.assign(1L, 12L);
        projects.save(orion);
        task(orion, "Cutover plan", today.plusDays(10), "open");
    }

    private void task(PmProject project, String title, LocalDate dueDate, String status) {
        PmTask task = new PmTask(project.getId(), title, dueDate, project.getOwnerUserId());
        task.assign(project.getOrgId(), project.getDeptId());
        if (!"open".equals(status)) {
            // A done task exists only to prove the risk analysis ignores it, so the seed marks it at
            // insert time rather than growing the entity's API.
            //
            // 一条 done 任务只是为了证明风险分析**忽略**它，故种子在插入时标好，不为实体扩 API。
            task.markDone();
        }
        tasks.save(task);
    }
}
