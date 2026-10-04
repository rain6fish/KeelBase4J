// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.scope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 *
 * <p><b>Not a {@code @Component} on purpose</b>, for the reason the level source gives: a scanned
 * component cannot be replaced, so a deployment whose departments live in its own directory would get
 * two beans of this type and a {@link ScopeFilter} whose constructor parameter matches neither name.
 * The promise in the paragraph above was unkeepable while this class was scanned — the same gap, in
 * the second of the two defaults a row range is assembled from.
 *
 * <p>部门树，**声明**的——交付档 A，与旁边的规则来源同档。
 *
 * <p>它存在，是为了让一个行范围能指名「某个部门及其以下」。参照实现给每个部门留一条物化的 `ancestors`
 * 路径、用它下钻子树；而**声明**的树用走父节点得出同一个集合——同一个答案，且不必维护一条冗余列。
 *
 * <p>档 B 的部署把这个 bean 换成**目录驱动**的树；下游一行不改，因为范围只问它 {@link #subtreeIds(long)}
 * 与 {@link #pathNames(Long)} 两件事。
 *
 * <p>**刻意不是 `@Component`**，理由与档位来源那处相同：**被扫描的组件换不掉**，于是把部门放在自己目录里的
 * 部署会得到两个同类型的 bean、以及一个构造参数名与两者都不配的 {@link ScopeFilter}。上面那段里的那句承诺，
 * 在这个类还被扫描的时候是**兑现不了**的——同一个缺口，出现在拼出一个行范围所需的**两个默认值里的第二个**上。
 */
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
