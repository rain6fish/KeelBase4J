// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.scope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The department tree, declared — delivery tier A, like the rule sources beside it.
 *
 * <p>It exists so a row range can name a department and everything under it. The reference keeps a
 * materialised {@code ancestors} path per department and drills the subtree with it; with a declared
 * tree the same set is derived by walking parents, which is the same answer without a denormalised
 * column to keep in step.
 *
 * <p>A tier B deployment replaces this bean with a directory-backed tree; nothing downstream changes,
 * because the range only ever asks it for {@link #subtreeIds(long)} and {@link #pathNames(Long)}.
 */
@Component
public class Departments {

    /** A declared department: its id, its name, and its parent ({@code null} at the root). */
    public record Dept(long id, String name, Long parentId) {
    }

    private final Map<Long, Dept> byId;

    /** A tree assembled by an alternative tier (B: directory-backed) without changing anything else. */
    public Departments(Map<Long, Dept> byId) {
        this.byId = Map.copyOf(byId);
    }

    /** The tier-A departments this spike declares: one sales organization, two levels. */
    public Departments() {
        this(Map.of(
                10L, new Dept(10, "Sales", null),
                11L, new Dept(11, "Sales — North", 10L),
                12L, new Dept(12, "Sales — South", 10L)));
    }

    public Optional<Dept> find(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** This department and everything beneath it — the set {@code own_dept_and_below} ranges over. */
    public Set<Long> subtreeIds(long id) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(id);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Dept dept : byId.values()) {
                if (dept.parentId() != null && ids.contains(dept.parentId()) && ids.add(dept.id())) {
                    grew = true;
                }
            }
        }
        return ids;
    }

    /**
     * The root → leaf department names, the form the identity contract's {@code deptPath} carries.
     * An unknown department has no path: it returns empty rather than inventing one.
     */
    public List<String> pathNames(Long id) {
        if (id == null || !byId.containsKey(id)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        Long current = id;
        while (current != null) {
            Dept dept = byId.get(current);
            if (dept == null) {
                // A parent outside the declared tree: report the part that is known and stop, rather
                // than pretending the path is complete.
                break;
            }
            names.add(0, dept.name());
            current = dept.parentId();
        }
        return names;
    }
}
