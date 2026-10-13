// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GovernancePolicyRepository extends JpaRepository<GovernancePolicyRow, Long> {
}
