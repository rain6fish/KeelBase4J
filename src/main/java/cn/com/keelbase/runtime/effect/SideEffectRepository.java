// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SideEffectRepository extends JpaRepository<SideEffect, Long> {

    Optional<SideEffect> findByIdempotencyKey(String idempotencyKey);

    List<SideEffect> findByUserIdOrderByIdDesc(String userId);
}
