// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PmTaskRepository extends JpaRepository<PmTask, Long> {

    List<PmTask> findByProjectIdAndDeletedAtIsNull(Long projectId);

    List<PmTask> findByOwnerUserIdAndDeletedAtIsNull(String ownerUserId);
}
