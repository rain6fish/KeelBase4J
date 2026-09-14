// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    List<Customer> findByOwnerUserId(String ownerUserId);

    List<Customer> findByOwnerUserIdOrId(String ownerUserId, Long id);
}
