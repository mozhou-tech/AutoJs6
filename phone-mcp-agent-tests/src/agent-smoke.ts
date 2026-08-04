#!/usr/bin/env node

import { Agent } from "@earendil-works/pi-agent-core";
import {
  createModels,
  createProvider,
  envApiKeyAuth,
  type Model,
} from "@earendil-works/pi-ai";
import { openAICompletionsApi } from "@earendil-works/pi-ai/api/openai-completions.lazy";
import { PhoneMcpClient } from "./client.js";
import { createPhoneMcpAgentTools } from "./pi-tools.js";

const REQUIRED_TOOLS = new Set(["phone_get_capabilities", "phone_get_permissions"]);
const SAFE_TOOLS = new Set([...REQUIRED_TOOLS, "phone_get_state", "phone_list_js_apis"]);

async function main(): Promise<void> {
  const apiKey = required("OPENAI_COMPAT_API_KEY");
  const baseUrl = required("OPENAI_COMPAT_BASE_URL");
  const modelId = required("OPENAI_COMPAT_MODEL");
  const model = compatibleModel(baseUrl, modelId);
  const provider = createProvider({
    id: "openai-compatible",
    name: "OpenAI-compatible endpoint",
    baseUrl,
    auth: { apiKey: envApiKeyAuth("OpenAI-compatible API key", ["OPENAI_COMPAT_API_KEY"]) },
    models: [model],
    api: openAICompletionsApi(),
  });
  const models = createModels();
  models.setProvider(provider);

  const phone = new PhoneMcpClient({
    url: process.env.PHONE_MCP_URL ?? "http://127.0.0.1:8765/mcp",
    token: required("PHONE_MCP_TOKEN"),
    timeoutMs: positiveInteger(process.env.PHONE_MCP_TIMEOUT_MS) ?? 30_000,
  });
  try {
    await phone.connect();
    const tools = createPhoneMcpAgentTools(await phone.listTools(), phone).filter((tool) => SAFE_TOOLS.has(tool.name));
    if (tools.length !== SAFE_TOOLS.size) throw new Error(`Expected ${SAFE_TOOLS.size} safe tools, found ${tools.length}.`);

    const calls: string[] = [];
    const agent = new Agent({
      initialState: {
        systemPrompt:
          "Test the connected PhoneMCP Android device using only supplied read-only tools. " +
          "Call phone_get_capabilities and phone_get_permissions. Report accessibility, screen capture, " +
          "OCR, and notification access exactly as returned; do not invent values.",
        model,
        thinkingLevel: "off",
        tools,
      },
      streamFn: models.streamSimple.bind(models),
      getApiKey: () => apiKey,
      beforeToolCall: async ({ toolCall }) => {
        if (!SAFE_TOOLS.has(toolCall.name)) return { block: true, reason: "Only read-only smoke tools are allowed." };
        calls.push(toolCall.name);
        console.log(`AGENT_TOOL_CALL ${toolCall.name}`);
        return undefined;
      },
    });
    await agent.prompt("Inspect the connected phone now and give a concise factual result.");

    const uniqueCalls = [...new Set(calls)];
    for (const requiredTool of REQUIRED_TOOLS) {
      if (!uniqueCalls.includes(requiredTool)) throw new Error(`Agent did not call required tool '${requiredTool}'.`);
    }
    console.log(`PASS agent_tools ${JSON.stringify(uniqueCalls)}`);
    console.log(`PASS agent_model ${modelId}`);
    const finalText = finalAssistantText(agent.state.messages);
    console.log(`AGENT_FINAL ${finalText}`);
  } finally {
    await phone.close();
  }
}

export function compatibleModel(baseUrl: string, modelId: string): Model<"openai-completions"> {
  return {
    id: modelId,
    name: modelId,
    api: "openai-completions",
    provider: "openai-compatible",
    baseUrl,
    reasoning: false,
    input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 128_000,
    maxTokens: 4_096,
    compat: {
      supportsStore: false,
      supportsReasoningEffort: false,
      supportsStrictMode: false,
      maxTokensField: "max_tokens",
      thinkingFormat: "qwen",
    },
  };
}

function finalAssistantText(messages: Array<{ role: string; content?: unknown }>): string {
  const message = [...messages].reverse().find((candidate) => candidate.role === "assistant");
  if (!message || !Array.isArray(message.content)) throw new Error("Agent produced no final assistant message.");
  const text = message.content
    .filter((item): item is { type: "text"; text: string } =>
      typeof item === "object" && item !== null && (item as { type?: unknown }).type === "text" &&
      typeof (item as { text?: unknown }).text === "string")
    .map((item) => item.text)
    .join("\n")
    .trim();
  if (!text) throw new Error("Agent produced no final text.");
  return text;
}

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required.`);
  return value;
}

function positiveInteger(raw: string | undefined): number | undefined {
  if (raw === undefined) return undefined;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error("PHONE_MCP_TIMEOUT_MS must be a positive integer.");
  return value;
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
