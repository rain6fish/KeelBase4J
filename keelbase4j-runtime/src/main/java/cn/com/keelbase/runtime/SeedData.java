// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime;

import cn.com.keelbase.runtime.domain.Customer;
import cn.com.keelbase.runtime.domain.CustomerRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Seeds two customers (one per user) so the app is usable by hand. Not active under tests. */
@Component
@Profile("!test")
public class SeedData implements CommandLineRunner {

    private final CustomerRepository customers;

    public SeedData(CustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    public void run(String... args) {
        if (customers.count() > 0) {
            return;
        }
        // Stamped with the organization and department they belong to, the way a create path would:
        // a row without them is reachable by its owner only, which is the tightening direction.
        Customer industrial = new Customer("Acme Industrial", "high", "alice");
        industrial.assign(1L, 11L);
        customers.save(industrial);

        Customer trading = new Customer("Globex Trading", "medium", "bob");
        trading.assign(1L, 12L);
        customers.save(trading);
    }
}
