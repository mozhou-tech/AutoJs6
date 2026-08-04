import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import type { McpToolCallResult, McpToolDefinition, McpToolCaller } from "./types.js";

export interface PhoneMcpClientOptions {
  url: string;
  token: string;
  timeoutMs?: number;
}

export class PhoneMcpClient implements McpToolCaller {
  readonly #client: Client;
  readonly #transport: StreamableHTTPClientTransport;
  readonly #timeoutMs: number;

  constructor(options: PhoneMcpClientOptions) {
    if (!options.token.trim()) {
      throw new Error("PHONE_MCP_TOKEN is required; copy the pairing token from the PhoneMCP settings page.");
    }
    const url = new URL(options.url);
    if (url.protocol !== "http:" && url.protocol !== "https:") {
      throw new Error(`PhoneMCP URL must use http or https, received: ${url.protocol}`);
    }
    this.#timeoutMs = options.timeoutMs ?? 30_000;
    this.#client = new Client({ name: "phone-mcp-pi-agent-tests", version: "0.1.0" });
    this.#transport = new StreamableHTTPClientTransport(url, {
      requestInit: {
        headers: {
          Authorization: `Bearer ${options.token}`,
        },
      },
    });
  }

  async connect(): Promise<void> {
    await this.#client.connect(this.#transport, { timeout: this.#timeoutMs });
  }

  async listTools(signal?: AbortSignal): Promise<McpToolDefinition[]> {
    const response = await this.#client.listTools(undefined, {
      signal,
      timeout: this.#timeoutMs,
    });
    return response.tools;
  }

  async callTool(
    name: string,
    args: Record<string, unknown>,
    signal?: AbortSignal,
  ): Promise<McpToolCallResult> {
    return this.#client.callTool(
      { name, arguments: args },
      undefined,
      { signal, timeout: this.#timeoutMs },
    );
  }

  async close(): Promise<void> {
    await this.#client.close();
  }
}
