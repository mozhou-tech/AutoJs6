import type { AgentTool, AgentToolResult } from "@earendil-works/pi-agent-core";
import { Type } from "typebox";
import type {
  McpToolCaller,
  McpToolCallResult,
  McpToolDefinition,
  PhoneMcpToolDetails,
} from "./types.js";

export function createPhoneMcpAgentTools(
  definitions: McpToolDefinition[],
  caller: McpToolCaller,
): AgentTool[] {
  return definitions.map((definition) => createPhoneMcpAgentTool(definition, caller));
}

export function createPhoneMcpAgentTool(
  definition: McpToolDefinition,
  caller: McpToolCaller,
): AgentTool {
  const parameters = Type.Unsafe<Record<string, unknown>>(definition.inputSchema);
  const readOnly = definition.annotations?.readOnlyHint === true;
  return {
    name: definition.name,
    label: definition.title ?? definition.annotations?.title ?? definition.name,
    description: definition.description ?? `Call the PhoneMCP tool ${definition.name}.`,
    parameters,
    executionMode: readOnly ? "parallel" : "sequential",
    execute: async (_toolCallId, params, signal) => {
      const result = await caller.callTool(definition.name, params as Record<string, unknown>, signal);
      if (isMcpToolError(result)) {
        throw new Error(toolErrorMessage(definition.name, result));
      }
      return {
        content: toPiContent(result),
        details: {
          toolName: definition.name,
          ...(hasStructuredContent(result) ? { structuredContent: result.structuredContent } : {}),
          mcpResult: result,
        } satisfies PhoneMcpToolDetails,
      };
    },
  };
}

function isMcpToolError(result: McpToolCallResult): boolean {
  return "isError" in result && result.isError === true;
}

function hasStructuredContent(
  result: McpToolCallResult,
): result is McpToolCallResult & { structuredContent: Record<string, unknown> } {
  return "structuredContent" in result && result.structuredContent !== undefined;
}

function toolErrorMessage(name: string, result: McpToolCallResult): string {
  const content = getMcpContent(result);
  const text = content
    .filter(isTextContent)
    .map((item) => item.text)
    .join("\n")
    .trim();
  return text ? `${name} failed: ${text}` : `${name} failed without an error message.`;
}

function toPiContent(result: McpToolCallResult): AgentToolResult<PhoneMcpToolDetails>["content"] {
  const content = getMcpContent(result);
  if (content.length === 0) {
    return [{ type: "text" as const, text: JSON.stringify(result) }];
  }
  const output: AgentToolResult<PhoneMcpToolDetails>["content"] = [];
  for (const item of content) {
    if (isTextContent(item)) {
      output.push({ type: "text", text: item.text });
      continue;
    }
    if (isImageContent(item)) {
      output.push({ type: "image", data: item.data, mimeType: item.mimeType });
      continue;
    }
    output.push({ type: "text", text: JSON.stringify(item) });
  }
  return output;
}

type McpContentItem = Record<string, unknown> & { type: string };
type McpTextContent = McpContentItem & { type: "text"; text: string };
type McpImageContent = McpContentItem & { type: "image"; data: string; mimeType: string };

function getMcpContent(result: McpToolCallResult): McpContentItem[] {
  const value = (result as { content?: unknown }).content;
  if (!Array.isArray(value)) return [];
  return value.filter(
    (item): item is McpContentItem =>
      typeof item === "object" && item !== null && typeof (item as { type?: unknown }).type === "string",
  );
}

function isTextContent(item: McpContentItem): item is McpTextContent {
  return item.type === "text" && typeof item.text === "string";
}

function isImageContent(item: McpContentItem): item is McpImageContent {
  return item.type === "image" && typeof item.data === "string" && typeof item.mimeType === "string";
}
