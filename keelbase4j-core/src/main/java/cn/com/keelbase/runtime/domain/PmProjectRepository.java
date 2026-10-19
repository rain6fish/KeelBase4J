// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PmProjectRepository extends JpaRepository<PmProject, Long>,
        JpaSpecificationExecutor<PmProject> {

    List<PmProject> findByOwnerUserId(String ownerUserId);
}
