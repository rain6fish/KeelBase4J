// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import cn.com.keelbase.runtime.domain.PmTask;
import cn.com.keelbase.runtime.domain.PmTaskRepository;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The PM reference project's local compensation: a revoked {@code pm_task} is soft-deleted in place.
 *
 * <p>It uses the compensation seam rather than a new one. {@link SideEffectService} asks the
 * registered compensators first, so this is the same path a deployment's own entity takes; the CRM
 * sample's built-in soft delete is the other shape of the same mechanism.
 *
 * <p>PM 参考项目的本地补偿：被撤销的 {@code pm_task} 就地软删。
 *
 * <p>它用的是**补偿缝**，不是另一条接缝。{@link SideEffectService} 先问已注册的补偿器，故这与部署自己的
 * 实体走的是同一条路；CRM 样例内置的软删是同一机制的另一形态。
 */
@Component
public class PmTaskCompensator implements SideEffectCompensator {

    private final PmTaskRepository tasks;

    public PmTaskCompensator(PmTaskRepository tasks) {
        this.tasks = tasks;
    }

    @Override
    public String resultType() {
        return "pm_task";
    }

    @Override
    public void compensate(SideEffect effect) {
        PmTask target = tasks.findById(effect.getResultId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "target not found"));
        target.setDeletedAt(Instant.now());
        tasks.save(target);
    }
}
