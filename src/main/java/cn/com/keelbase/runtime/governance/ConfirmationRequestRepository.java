// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.governance;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfirmationRequestRepository extends JpaRepository<ConfirmationRequest, Long> {

    Optional<ConfirmationRequest> findByToken(String token);
}
