// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GovernancePolicyHistoryRepository extends JpaRepository<GovernancePolicyHistoryRow, Long> {

    /** The most recent snapshot, used to keep an unchanged submission from appending a duplicate. */
    Optional<GovernancePolicyHistoryRow> findFirstByOrderByIdDesc();
}
