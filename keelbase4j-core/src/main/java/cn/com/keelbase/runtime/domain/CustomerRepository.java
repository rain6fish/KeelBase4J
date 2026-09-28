// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CustomerRepository extends JpaRepository<Customer, Long>,
        JpaSpecificationExecutor<Customer> {

    List<Customer> findByOwnerUserId(String ownerUserId);

    List<Customer> findByOwnerUserIdOrId(String ownerUserId, Long id);
}
