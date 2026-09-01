package com.paicli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/*
*   一个 Java record（数据载体内型），用来描述 MCP Server 上的某一个工具的完整元信息。
* */

public record McpToolDescriptor(
        String serverName,          // 所属 MCP Server 名称（如 "chrome-devtools"）
        String name,                // 工具原始名称（如 "list_pages"）
        String namespacedName,      // 全局唯一名：mcp__chrome-devtools__list_pages
        String description,         // 工具描述
        JsonNode inputSchema
) {
    public static String namespaced(String serverName, String toolName) {
        return "mcp__" + serverName + "__" + toolName;
    }
}
