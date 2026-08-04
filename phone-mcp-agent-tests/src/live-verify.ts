import { createPhoneMcpAgentTools } from "./pi-tools.js";
import type { McpToolCaller, McpToolDefinition, PhoneMcpToolDetails } from "./types.js";

export interface LiveVerifySummary {
  catalogSize: number;
  readTools: string[];
  capabilityChecks: string[];
  rejectionChecks: string[];
  bridgeResult: unknown;
  leaseReleased: boolean;
}

const READ_CASES: Array<[string, Record<string, unknown>]> = [
  ["phone_get_state", {}],
  ["phone_get_transport_status", {}],
  ["phone_get_permissions", {}],
  ["phone_list_js_apis", { query: "base64", limit: 10 }],
  ["phone_wait_for", { condition: "screen_on", timeout_ms: 1_000 }],
  ["phone_list_apps", { query: "PhoneMCP", limit: 5 }],
  ["phone_get_app_info", { package_name: "org.autojs.autojs6" }],
  ["phone_get_jobs", { limit: 5 }],
];

export async function verifyLivePhone(
  definitions: McpToolDefinition[],
  caller: McpToolCaller,
  log: (message: string) => void = () => undefined,
): Promise<LiveVerifySummary> {
  if (definitions.length !== 36) {
    throw new Error(`Expected 36 PhoneMCP tools, server advertised ${definitions.length}.`);
  }
  const tools = new Map(createPhoneMcpAgentTools(definitions, caller).map((tool) => [tool.name, tool]));
  const call = async (name: string, args: Record<string, unknown>) => {
    const tool = tools.get(name);
    if (!tool) throw new Error(`Required verification tool '${name}' is missing.`);
    return tool.execute(`verify-${name}-${Date.now()}`, args);
  };

  const readTools: string[] = [];
  const capabilities = details(await call("phone_get_capabilities", {}));
  readTools.push("phone_get_capabilities");
  log("PASS read phone_get_capabilities");
  for (const [name, args] of READ_CASES) {
    await call(name, args);
    readTools.push(name);
    log(`PASS read ${name}`);
  }

  const flags = objectField(capabilities, "capabilities");
  const capabilityChecks: string[] = [];
  await verifyCapability(
    call,
    "phone_ui_snapshot",
    { max_nodes: 10, visible_only: true },
    flags.accessibility === true,
    ["ACCESSIBILITY_DISABLED"],
  );
  capabilityChecks.push("accessibility");
  log("PASS capability accessibility");
  await verifyCapability(
    call,
    "phone_capture_screen",
    { format: "jpeg", quality: 50, max_width: 320 },
    flags.screen_capture === true,
    ["ACCESSIBILITY_DISABLED", "SCREEN_CAPTURE_DISABLED"],
  );
  capabilityChecks.push("screen_capture");
  log("PASS capability screen_capture");
  await verifyCapability(
    call,
    "phone_ocr_read",
    { min_confidence: 0.5 },
    flags.ocr === true,
    ["ACCESSIBILITY_DISABLED", "SCREEN_CAPTURE_DISABLED"],
  );
  capabilityChecks.push("ocr");
  log("PASS capability ocr");
  await verifyCapability(
    call,
    "phone_get_notifications",
    { limit: 1 },
    flags.notification_access === true,
    ["PERMISSION_REQUIRED"],
  );
  capabilityChecks.push("notification_access");
  log("PASS capability notification_access");

  await expectFailure(() => call("phone_get_state", { unexpected: true }), "INVALID_ARGUMENT");
  log("PASS rejection schema_validation");
  await expectFailure(
    () => call("phone_toast", { lease_id: "invalid-lease", action: "show", text: "blocked" }),
    "AUTHENTICATION_REQUIRED",
  );
  log("PASS rejection lease_required");

  const acquired = details(await call("phone_session_control", { action: "acquire", ttl_seconds: 60 }));
  const leaseId = stringField(acquired, "lease_id");
  let bridgeResult: unknown;
  let released = false;
  try {
    const bridge = details(
      await call("phone_call_js_api", {
        lease_id: leaseId,
        api: "base64.encode",
        arguments: ["PhoneMCP"],
        result_mode: "json",
      }),
    );
    bridgeResult = bridge.result;
    if (bridgeResult !== "UGhvbmVNQ1A=") {
      throw new Error(`Unexpected base64 bridge result: ${JSON.stringify(bridgeResult)}`);
    }
    log("PASS control phone_call_js_api");
  } finally {
    await call("phone_session_control", { action: "release", lease_id: leaseId });
    released = true;
  }

  const finalState = details(await call("phone_get_state", {}));
  const leaseState = objectField(finalState, "control_lease");
  if (leaseState.active !== false) throw new Error("Control lease remained active after verification.");
  log("PASS cleanup lease_released");

  return {
    catalogSize: definitions.length,
    readTools,
    capabilityChecks,
    rejectionChecks: ["schema_validation", "lease_required"],
    bridgeResult,
    leaseReleased: released,
  };
}

function details(result: Awaited<ReturnType<ReturnType<typeof createPhoneMcpAgentTools>[number]["execute"]>>) {
  const value = result.details as PhoneMcpToolDetails;
  if (!value.structuredContent) throw new Error(`Tool '${value.toolName}' returned no structured content.`);
  return value.structuredContent;
}

async function expectFailure(action: () => Promise<unknown>, code: string): Promise<void> {
  return expectAnyFailure(action, [code]);
}

async function expectAnyFailure(action: () => Promise<unknown>, codes: string[]): Promise<void> {
  try {
    await action();
  } catch (error) {
    if (error instanceof Error && codes.some((code) => error.message.includes(code))) return;
    throw error;
  }
  throw new Error(`Expected MCP failure containing one of: ${codes.join(", ")}.`);
}

async function verifyCapability(
  call: (name: string, args: Record<string, unknown>) => Promise<unknown>,
  name: string,
  args: Record<string, unknown>,
  available: boolean,
  unavailableCodes: string[],
): Promise<void> {
  if (available) await call(name, args);
  else await expectAnyFailure(() => call(name, args), unavailableCodes);
}

function stringField(value: Record<string, unknown>, key: string): string {
  const field = value[key];
  if (typeof field !== "string" || !field) throw new Error(`Expected non-empty string field '${key}'.`);
  return field;
}

function objectField(value: Record<string, unknown>, key: string): Record<string, unknown> {
  const field = value[key];
  if (typeof field !== "object" || field === null || Array.isArray(field)) {
    throw new Error(`Expected object field '${key}'.`);
  }
  return field as Record<string, unknown>;
}
