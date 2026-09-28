// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.web;

import cn.com.keelbase.runtime.authz.OwnershipGuard;
import cn.com.keelbase.runtime.authz.PermissionAuthorizer;
import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import cn.com.keelbase.runtime.identity.CurrentPrincipal;
import cn.com.keelbase.runtime.identity.Principal;
import cn.com.keelbase.runtime.scope.ScopeFilter;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The list half of the trust boundary: rows the caller may see, and only those.
 *
 * <p>The coarse gate runs first — a caller with no read capability is refused before any row is
 * considered. Then the row half is the caller's data range, expressed as a typed predicate rather
 * than a filter applied afterwards: filtering a fetched list would read rows the caller may not see,
 * and would make the restriction a property of this method rather than of the range.
 */
@RestController
public class CustomerController {

    private final CurrentPrincipal principals;
    private final CustomerRepository customers;
    private final OwnershipGuard guard;
    private final ScopeFilter scopes;

    public CustomerController(CurrentPrincipal principals, CustomerRepository customers,
                              OwnershipGuard guard, ScopeFilter scopes) {
        this.principals = principals;
        this.customers = customers;
        this.guard = guard;
        this.scopes = scopes;
    }

    @GetMapping("/customers")
    public List<Customer> list() {
        Principal principal = principals.current();
        guard.requireAction(principal, "Customer", PermissionAuthorizer.ACTION_READ);
        if (!ScopeFilter.filterable("Customer")) {
            // An entity that is not scope-filterable leaves the caller to supply its own condition.
            // This one has none, and seeing every row is not an acceptable stand-in for it.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer is not scope-filterable");
        }
        Specification<Customer> range =
                scopes.<Customer>restrict("Customer", principal, scopes.levelFor(principal)).orElse(null);
        return range == null ? customers.findAll() : customers.findAll(range);
    }
}
