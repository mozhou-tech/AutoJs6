#!/usr/bin/env node

import { parseArgs } from "node:util";
import type { AgentTool } from "@earendil-works/pi-agent-core";
import { PhoneMcpClient } from "./client.js";
import { verifyLivePhone } from "./live-verify.js";
import { createPhoneMcpAgentTools } from "./pi-tools.js";

const READ_ONLY_SMOKE_TOOLS = [
  "phone_get_capabilities",
  "phone_get_state",
  "phone_get_permissions",
] as const;

async function main(): Promise<void> {
  const { positionals, values } = parseArgs({
    allowPositionals: true,
    options: {
      tool: { type: "string" },
      args: { type: "string", default: "{}" },
      json: { type: "boolean", default: false },
      help: { type: "boolean", short: "h", default: false },
    },
  });
  const command = positionals[0] ?? "help";
  if (values.help || command === "help") {
    printHelp();
    return;
  }

  const client = new PhoneMcpClient({
    url: process.env.PHONE_MCP_URL ?? "http://127.0.0.1:8765/mcp",
    token: process.env.PHONE_MCP_TOKEN ?? "",
    timeoutMs: parseTimeout(process.env.PHONE_MCP_TIMEOUT_MS),
  });
  try {
    await client.connect();
    const definitions = await client.listTools();
    const tools = createPhoneMcpAgentTools(definitions, client);
    switch (command) {
      case "list":
        printTools(tools, values.json);
        break;
      case "call":
        await callTool(tools, values.tool, values.args ?? "{}");
        break;
      case "smoke":
        await runSmoke(tools);
        break;
      case "verify":
        await verifyLivePhone(definitions, client, console.log);
        break;
      default:
        throw new Error(`Unknown command '${command}'. Run with --help for usage.`);
    }
  } finally {
    await client.close();
  }
}

function printTools(tools: AgentTool[], json: boolean): void {
  const rows = tools.map((tool) => ({
    name: tool.name,
    mode: tool.executionMode ?? "parallel",
    description: tool.description,
  }));
  if (json) console.log(JSON.stringify(rows, null, 2));
  else console.table(rows);
}

async function callTool(tools: AgentTool[], name: string | undefined, rawArgs: string): Promise<void> {
  if (!name) throw new Error("The call command requires --tool <name>.");
  const tool = tools.find((candidate) => candidate.name === name);
  if (!tool) throw new Error(`PhoneMCP did not advertise a tool named '${name}'.`);
  const args = parseObject(rawArgs);
  const result = await tool.execute(`cli-${Date.now()}`, args);
  console.log(JSON.stringify(result.details, null, 2));
}

async function runSmoke(tools: AgentTool[]): Promise<void> {
  if (tools.length !== 38) {
    throw new Error(`Expected 38 PhoneMCP tools, server advertised ${tools.length}.`);
  }
  if (tools.some((tool) => !tool.name.startsWith("phone_"))) {
    throw new Error("The server advertised a tool without the required phone_ prefix.");
  }
  for (const name of READ_ONLY_SMOKE_TOOLS) {
    const tool = tools.find((candidate) => candidate.name === name);
    if (!tool) throw new Error(`Required smoke tool '${name}' is missing.`);
    if (tool.executionMode !== "parallel") {
      throw new Error(`Smoke tool '${name}' was not marked read-only by the server.`);
    }
    const result = await tool.execute(`smoke-${name}`, {});
    const details = result.details as { structuredContent?: unknown };
    console.log(`PASS ${name}: ${JSON.stringify(details.structuredContent ?? result.content)}`);
  }
  console.log(`PASS catalog: ${tools.length} Pi Agent tools discovered`);
}

function parseObject(raw: string): Record<string, unknown> {
  const parsed: unknown = JSON.parse(raw);
  if (parsed === null || Array.isArray(parsed) || typeof parsed !== "object") {
    throw new Error("--args must be a JSON object.");
  }
  return parsed as Record<string, unknown>;
}

function parseTimeout(raw: string | undefined): number | undefined {
  if (raw === undefined) return undefined;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error("PHONE_MCP_TIMEOUT_MS must be a positive integer.");
  }
  return value;
}

function printHelp(): void {
  console.log(`PhoneMCP Pi Agent SDK test harness

Environment:
  PHONE_MCP_TOKEN       Pairing token (required)
  PHONE_MCP_URL         MCP endpoint (default: http://127.0.0.1:8765/mcp)
  PHONE_MCP_TIMEOUT_MS  Request timeout (default: 30000)

Commands:
  list [--json]                         List MCP tools adapted as Pi Agent tools
  call --tool <name> [--args '<json>'] Call one tool through the Pi adapter
  smoke                                Run three safe read-only calls
  verify                               Run extended read, rejection, lease and JS bridge checks
`);
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
