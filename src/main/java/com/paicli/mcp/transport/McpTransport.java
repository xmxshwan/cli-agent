package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/*
  ▎ McpTransport 是 MCP 通信的传输层抽象接口，定义了 send() 和 onReceive() 两个核心方法。StdioTransport
  通过子进程 stdin/stdout 实现本地 MCP Server 通信，StreamableHttpTransport
  ▎ 通过 HTTP POST + SSE 实现远程 MCP Server 通信。上层 JsonRpcClient 和 McpClient 只依赖这个接口，无需关心
  底层是子进程还是 HTTP——这就是面向接口编程的核心价值。
* */

public interface McpTransport extends AutoCloseable {
    void send(JsonNode message) throws IOException;

    void onReceive(Consumer<JsonNode> listener);

    default List<String> stderrLines() {
        return List.of();
    }

    default Long processId() {
        return null;
    }

    default String transportName() {
        return "unknown";
    }

    @Override
    void close();
}
