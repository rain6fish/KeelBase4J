// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FollowUpRepository extends JpaRepository<FollowUp, Long> {

    List<FollowUp> findByCustomerIdAndDeletedAtIsNull(Long customerId);

    List<FollowUp> findByOwnerUserIdAndDeletedAtIsNull(String ownerUserId);
}
