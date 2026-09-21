// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.effect;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SideEffectRepository extends JpaRepository<SideEffect, Long> {

    Optional<SideEffect> findByIdempotencyKey(String idempotencyKey);

    List<SideEffect> findByUserIdOrderByIdDesc(String userId);

    /** The same view, paged at the database — which is what the console asks for. */
    Page<SideEffect> findByUserIdOrderByIdDesc(String userId, Pageable pageable);
}
