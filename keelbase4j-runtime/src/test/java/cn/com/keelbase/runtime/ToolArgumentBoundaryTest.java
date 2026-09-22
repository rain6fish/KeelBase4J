// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.domain.FollowUpRepository;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.tool.AnalyzeCustomerRiskTool;
import cn.com.keelbase.runtime.tool.CreateFollowUpTool;
import cn.com.keelbase.runtime.tool.ToolResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * JV-28 ② — the tool boundary does not trust the caller's arguments.
 *
 * <p>The planner's output is a proposal, not a guarantee, however firmly the tool schema marks a
 * field required. When that proposal carried no id (or something that is not one), the id reached
 * {@code findById(null)}, Spring Data threw, and the throw surfaced as a 500 — an
 * <em>ungoverned</em> answer to input the runtime should simply have refused. A tool failure is an
 * outcome the caller can read and act on; a server fault is not.
 *
 * <p>The last case is the control: the boundary refuses bad input without also refusing good input.
 */
class ToolArgumentBoundaryTest {

    private static final Principal ALICE = new Principal("alice", "user");

    @Test
    void aReadToolRefusesAMissingIdRatherThanCrashing() {
        CustomerRepository customers = mock(CustomerRepository.class);
        AnalyzeCustomerRiskTool tool = new AnalyzeCustomerRiskTool(customers, mock(OwnershipGuard.class));

        ToolResult result = tool.execute(new HashMap<>(), ALICE);

        assertFalse(result.success(), "a missing id is a refusal, not a result");
        verifyNoInteractions(customers);
    }

    @Test
    void aReadToolRefusesAnIdThatIsNotANumber() {
        CustomerRepository customers = mock(CustomerRepository.class);
        AnalyzeCustomerRiskTool tool = new AnalyzeCustomerRiskTool(customers, mock(OwnershipGuard.class));

        ToolResult result = tool.execute(Map.of("customerId", "the first one"), ALICE);

        assertFalse(result.success(), "\"the first one\" is not an id — it must be refused, not thrown on");
        verifyNoInteractions(customers);
    }

    @Test
    void aWriteToolRefusesAMissingIdRatherThanCrashing() {
        CustomerRepository customers = mock(CustomerRepository.class);
        CreateFollowUpTool tool = new CreateFollowUpTool(
                customers, mock(FollowUpRepository.class), mock(OwnershipGuard.class));

        ToolResult result = tool.execute(new HashMap<>(), ALICE);

        assertFalse(result.success(), "a write handed no customer must be refused before anything is written");
        verifyNoInteractions(customers);
    }

    @Test
    void aWriteToolRefusesAnIdThatIsNotANumber() {
        CustomerRepository customers = mock(CustomerRepository.class);
        CreateFollowUpTool tool = new CreateFollowUpTool(
                customers, mock(FollowUpRepository.class), mock(OwnershipGuard.class));

        ToolResult result = tool.execute(Map.of("customerId", "1x"), ALICE);

        assertFalse(result.success());
        verifyNoInteractions(customers);
    }

    @Test
    void aValidIdStillGoesThrough() {
        CustomerRepository customers = mock(CustomerRepository.class);
        when(customers.findById(7L)).thenReturn(Optional.of(new Customer("Acme", "high", "alice")));
        AnalyzeCustomerRiskTool tool = new AnalyzeCustomerRiskTool(customers, mock(OwnershipGuard.class));

        ToolResult result = tool.execute(Map.of("customerId", 7), ALICE);

        assertTrue(result.success(), "the guard must refuse bad input, not good input");
    }
}
