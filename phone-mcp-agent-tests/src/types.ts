import type { Client } from "@modelcontextprotocol/sdk/client/index.js";

export type McpToolDefinition = Awaited<ReturnType<Client["listTools"]>>["tools"][number];
export type McpToolCallResult = Awaited<ReturnType<Client["callTool"]>>;

export interface McpToolCaller {
  callTool(
    name: string,
    args: Record<string, unknown>,
    signal?: AbortSignal,
  ): Promise<McpToolCallResult>;
}

export interface PhoneMcpToolDetails {
  toolName: string;
  structuredContent?: Record<string, unknown>;
  mcpResult: McpToolCallResult;
}
