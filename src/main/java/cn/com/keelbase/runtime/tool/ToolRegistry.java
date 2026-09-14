// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Registry of available tools, discovered from the Spring context. */
@Component
public class ToolRegistry {

    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AiTool> beans) {
        for (AiTool tool : beans) {
            tools.put(tool.name(), tool);
        }
    }

    public AiTool require(String name) {
        AiTool tool = tools.get(name);
        if (tool == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown tool: " + name);
        }
        return tool;
    }

    public List<AiTool> all() {
        return List.copyOf(tools.values());
    }
}
