// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.coretest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.audit.AuditReadLimits;
import cn.com.keelbase.runtime.audit.AuditService;
import cn.com.keelbase.runtime.authz.GovernanceReadSubjects;
import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.web.AuditController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * An owner-scoped grant is narrowed to the caller — and it is refused, not widened, when the caller
 * has no local user id to narrow to.
 *
 * <p>{@code AuditService.rows} drops the caller predicate when the id is null, so an owner scope
 * resolving to null would answer every caller's rows. The confirmation and operations faces already
 * fail closed on the same input (a null key matches no rows); this pins the audit face to the same
 * direction rather than a cross-caller read.
 *
 * 带主人范围的授予收窄到调用者 —— 而当调用者没有可收窄到的本地 user id 时，它是**被拒绝**的，不是被放宽的。
 *
 * <p>{@code AuditService.rows} 在 id 为 null 时会丢掉调用方谓词，故解析成 null 的主人范围会回出所有调用方
 * 的行。确认面与操作审计面在同样的输入上已经失败即关（空键不匹配任何行）；这条测试把审计面钉在同方向上，
 * 而不是一次跨调用方的读。
 */
class AuditControllerOwnerScopeTest {

    private final AuditService audit = mock(AuditService.class);
    private final CurrentPrincipal principals = mock(CurrentPrincipal.class);
    private final OwnershipGuard guard = mock(OwnershipGuard.class);

    private final AuditController controller =
            new AuditController(audit, principals, guard, AuditReadLimits.defaults());

    @Test
    void anOwnerScopedGrantWithoutALocalUserIdIsRefusedRatherThanWidened() {
        when(principals.current()).thenReturn(new Principal(null, Principal.ROLE_USER));
        when(guard.ownerScoped(any(), eq(GovernanceReadSubjects.AUDIT_LOG))).thenReturn(true);

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> controller.logs(null, null, null, null, null, null, null, null, null, null, null));

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        verifyNoInteractions(audit);
    }

    @Test
    void anOwnerScopedGrantAnswersTheCallersOwnRows() {
        when(principals.current()).thenReturn(new Principal("bob", Principal.ROLE_USER));
        when(guard.ownerScoped(any(), eq(GovernanceReadSubjects.AUDIT_LOG))).thenReturn(true);

        controller.logs("alice", null, null, null, null, null, null, null, null, 10, 0);

        verify(audit).rows("bob", null, null, null, 10, 0, null);
    }
}
