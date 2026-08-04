import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { afterEach, describe, expect, it } from "vitest";
import { createPhoneMcpAgentTools } from "../src/pi-tools.js";
import type { McpToolCaller } from "../src/types.js";

describe("Pi Agent tools over an MCP transport", () => {
  const closeables: Array<{ close(): Promise<void> }> = [];

  afterEach(async () => {
    await Promise.all(closeables.splice(0).map((closeable) => closeable.close()));
  });

  it("discovers and invokes an MCP tool through the Pi adapter", async () => {
    const server = new McpServer({ name: "phone-mcp-test-server", version: "1.0.0" });
    server.registerTool(
      "phone_get_state",
      {
        title: "Get phone state",
        description: "Return a deterministic phone state for integration testing.",
        inputSchema: { verbose: z.boolean().optional() },
        annotations: {
          readOnlyHint: true,
          destructiveHint: false,
          idempotentHint: true,
          openWorldHint: false,
        },
      },
      async ({ verbose }) => {
        const state = { ok: true, screen_on: true, verbose: verbose ?? false };
        return {
          content: [{ type: "text", text: JSON.stringify(state) }],
          structuredContent: state,
        };
      },
    );

    const client = new Client({ name: "pi-agent-test-client", version: "1.0.0" });
    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    closeables.push(client, server);

    const definitions = (await client.listTools()).tools;
    const caller: McpToolCaller = {
      callTool: (name, args, signal) =>
        client.callTool({ name, arguments: args }, undefined, { signal, timeout: 5_000 }),
    };
    const [tool] = createPhoneMcpAgentTools(definitions, caller);

    expect(tool?.name).toBe("phone_get_state");
    const result = await tool?.execute("integration-call", { verbose: true });
    expect(result?.details).toMatchObject({
      toolName: "phone_get_state",
      structuredContent: { ok: true, screen_on: true, verbose: true },
    });
  });
});
