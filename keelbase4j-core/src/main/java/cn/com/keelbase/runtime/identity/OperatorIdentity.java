// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.identity;

import cn.com.keelbase.protocol.CanonicalJson;
import cn.com.keelbase.protocol.Json;
import cn.com.keelbase.protocol.OrgMembershipScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The initiator's identity in the form a confirmation row carries it (ADR-0018).
 *
 * <p>An approval row is answered by <em>somebody other than</em> the person who raised it, and the
 * write is then performed as that person. By the time it is answered the initiator is not the caller,
 * so their identity has to be read back off the row — which is why it is written down when the row is
 * created rather than looked up later. (Looking it up would also mean the write ran under whatever
 * role the person holds <em>now</em>; what the row should honour is the identity it was raised
 * under.)
 *
 * <p>The stored shape is the wire form of {@link Principal}, so what is persisted is what would have
 * travelled on the wire — not a private serialisation of a Java object.
 */
public final class OperatorIdentity {

    private OperatorIdentity() {
    }

    /** The wire form: {@code {userId, role, oidcSubject, org}} — {@code org} is the protocol's own shape. */
    public static String toWireJson(Principal principal) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("userId", principal.userId());
        wire.put("role", principal.role());
        wire.put("oidcSubject", principal.oidcSubject());
        wire.put("org", principal.org() == null ? null : principal.org().toWire());
        return CanonicalJson.json(wire);
    }

    /**
     * Read a principal back, or {@code null} when the row carries no identity — a row written before
     * this column existed. The caller decides what to fall back to; this method does not invent one.
     */
    public static Principal fromWireJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Map<String, Object> wire = map(Json.parse(json));
        if (wire == null || !(wire.get("userId") instanceof String userId)) {
            return null;
        }
        return new Principal(userId, string(wire.get("role")), string(wire.get("oidcSubject")),
                org(map(wire.get("org"))));
    }

    private static OrgMembershipScope org(Map<String, Object> wire) {
        if (wire == null) {
            return null;
        }
        Map<String, Object> orgWire = map(wire.get("org"));
        if (orgWire == null) {
            return null;
        }
        OrgMembershipScope.Org org = new OrgMembershipScope.Org(
                longValue(orgWire.get("id")), string(orgWire.get("name")), string(orgWire.get("description")));
        return new OrgMembershipScope(org, string(wire.get("role")), longValue(wire.get("deptId")),
                strings(wire.get("deptPath")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static String string(Object value) {
        return value instanceof String s ? s : null;
    }

    private static Long longValue(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }
}
