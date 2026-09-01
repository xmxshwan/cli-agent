package com.paicli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/*
*  ▎ McpSchemaSanitizer 是 MCP 工具注册前的"净化器"，递归裁剪 JSON Schema 中的 $schema、$id、$ref 等无用元数据，将 anyOf/oneOf 合并为 description 文本，并截断超长描述，最终让 LLM
  ▎ 看到的工具参数定义简洁干净，平均节省 30-60% 的 Schema Token 占用，相当于把 LLM 的上下文窗口"变大"了。
  *
  *  McpSchemaSanitizer 本质上是一个协议适配器——它把通用的 MCP 协议输出，适配到 LLM 这个特殊"消费者"能理解的格式上。不是那些元数据没用，而是对 LLM 这个特定的消费者没用罢了。

* */

public final class McpSchemaSanitizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DESCRIPTION_CHARS = 1000;

    private McpSchemaSanitizer() {
    }

    public static JsonNode sanitize(JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.put("type", "object");
            fallback.putObject("properties");
            return fallback;
        }
        JsonNode copy = schema.deepCopy();
        JsonNode cleaned = clean(copy);
        if (!cleaned.isObject()) {
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.put("type", "object");
            fallback.set("description", cleaned);
            return fallback;
        }
        ObjectNode obj = (ObjectNode) cleaned;
        if (!obj.has("type")) {
            obj.put("type", "object");
        }
        if (!obj.has("properties")) {
            obj.putObject("properties");
        }
        return obj;
    }

    private static JsonNode clean(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove("$schema");
            object.remove("$id");
            object.remove("$ref");

            StringBuilder alternatives = new StringBuilder();
            for (String keyword : new String[]{"anyOf", "oneOf"}) {
                JsonNode union = object.remove(keyword);
                if (union != null && union.isArray()) {
                    if (!alternatives.isEmpty()) {
                        alternatives.append("; ");
                    }
                    alternatives.append(keyword).append(" options: ");
                    for (int i = 0; i < union.size(); i++) {
                        if (i > 0) alternatives.append(", ");
                        JsonNode option = union.get(i);
                        alternatives.append(option.path("type").asText(option.toString()));
                    }
                }
            }
            if (!alternatives.isEmpty()) {
                object.put("type", "object");
                String existing = object.path("description").asText("");
                object.put("description", truncateDescription(
                        existing.isBlank() ? alternatives.toString() : existing + " (" + alternatives + ")"));
            }

            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode child = field.getValue();
                if ("description".equals(field.getKey()) && child.isTextual()) {
                    object.put("description", truncateDescription(child.asText()));
                } else {
                    clean(child);
                }
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (JsonNode child : array) {
                clean(child);
            }
        }
        return node;
    }

    private static String truncateDescription(String description) {
        if (description == null || description.length() <= MAX_DESCRIPTION_CHARS) {
            return description;
        }
        return description.substring(0, MAX_DESCRIPTION_CHARS) + "...";
    }
}
