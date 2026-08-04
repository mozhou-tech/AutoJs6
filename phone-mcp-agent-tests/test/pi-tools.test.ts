import { describe, expect, it, vi } from "vitest";
import { createPhoneMcpAgentTool, createPhoneMcpAgentTools } from "../src/pi-tools.js";
import type { McpToolCaller, McpToolDefinition } from "../src/types.js";

describe("PhoneMCP to Pi Agent tool adapter", () => {
  it("preserves schemas and maps safety annotations to execution modes", () => {
    const caller = callerReturning(textResult("ok"));
    const readTool = createPhoneMcpAgentTool(toolDefinition("phone_get_state", true), caller);
    const writeTool = createPhoneMcpAgentTool(toolDefinition("phone_input_text", false), caller);

    expect(readTool.executionMode).toBe("parallel");
    expect(writeTool.executionMode).toBe("sequential");
    expect(readTool.parameters).toMatchObject({
      type: "object",
      properties: { verbose: { type: "boolean" } },
      additionalProperties: false,
    });
  });

  it("calls MCP with Pi arguments, forwards abort, and retains structured details", async () => {
    const callTool = vi.fn<McpToolCaller["callTool"]>().mockResolvedValue({
      content: [
        { type: "text", text: "captured" },
        { type: "image", data: "aW1hZ2U=", mimeType: "image/png" },
      ],
      structuredContent: { ok: true, state_version: 42 },
      isError: false,
    });
    const tool = createPhoneMcpAgentTool(toolDefinition("phone_capture_screen", true), { callTool });
    const controller = new AbortController();

    const result = await tool.execute("tool-call-1", { max_width: 720 }, controller.signal);

    expect(callTool).toHaveBeenCalledWith("phone_capture_screen", { max_width: 720 }, controller.signal);
    expect(result.content).toEqual([
      { type: "text", text: "captured" },
      { type: "image", data: "aW1hZ2U=", mimeType: "image/png" },
    ]);
    expect(result.details).toMatchObject({
      toolName: "phone_capture_screen",
      structuredContent: { ok: true, state_version: 42 },
    });
  });

  it("throws MCP tool errors so Pi records an error tool result", async () => {
    const tool = createPhoneMcpAgentTool(
      toolDefinition("phone_ui_action", false),
      callerReturning({
        content: [{ type: "text", text: "LEASE_REQUIRED: acquire a control lease" }],
        isError: true,
      }),
    );

    await expect(tool.execute("tool-call-2", {})).rejects.toThrow(
      "phone_ui_action failed: LEASE_REQUIRED: acquire a control lease",
    );
  });

  it("adapts a complete catalog without renaming tools", () => {
    const definitions = [
      toolDefinition("phone_get_state", true),
      toolDefinition("phone_input_text", false),
    ];
    const tools = createPhoneMcpAgentTools(definitions, callerReturning(textResult("ok")));
    expect(tools.map((tool) => tool.name)).toEqual(["phone_get_state", "phone_input_text"]);
  });
});

function toolDefinition(name: string, readOnly: boolean): McpToolDefinition {
  return {
    name,
    title: name,
    description: `Test ${name}`,
    inputSchema: {
      type: "object",
      properties: { verbose: { type: "boolean" } },
      additionalProperties: false,
    },
    annotations: {
      readOnlyHint: readOnly,
      destructiveHint: !readOnly,
      idempotentHint: readOnly,
      openWorldHint: false,
    },
  };
}

function callerReturning(result: Awaited<ReturnType<McpToolCaller["callTool"]>>): McpToolCaller {
  return { callTool: async () => result };
}

function textResult(text: string) {
  return { content: [{ type: "text" as const, text }], isError: false };
}
