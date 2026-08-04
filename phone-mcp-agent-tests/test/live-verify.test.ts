import { describe, expect, it, vi } from "vitest";
import { verifyLivePhone } from "../src/live-verify.js";
import type { McpToolCaller, McpToolDefinition } from "../src/types.js";

describe("extended live verifier", () => {
  it("checks reads and rejections, runs the bridge under a lease, and releases it", async () => {
    let leaseActive = false;
    const calls: Array<{ name: string; args: Record<string, unknown> }> = [];
    const caller: McpToolCaller = {
      callTool: async (name, args) => {
        calls.push({ name, args });
        if (name === "phone_get_state" && "unexpected" in args) return failure("INVALID_ARGUMENT");
        if (name === "phone_toast") return failure("AUTHENTICATION_REQUIRED");
        if (name === "phone_ui_snapshot" || name === "phone_capture_screen" || name === "phone_ocr_read") {
          return failure("ACCESSIBILITY_DISABLED");
        }
        if (name === "phone_get_notifications") return failure("PERMISSION_REQUIRED");
        if (name === "phone_get_capabilities") {
          return success({
            capabilities: {
              accessibility: false,
              screen_capture: false,
              ocr: false,
              notification_access: false,
            },
          });
        }
        if (name === "phone_session_control" && args.action === "acquire") {
          leaseActive = true;
          return success({ lease_id: "lease-1" });
        }
        if (name === "phone_session_control" && args.action === "release") {
          leaseActive = false;
          return success({ active: false });
        }
        if (name === "phone_call_js_api") return success({ result: "UGhvbmVNQ1A=" });
        if (name === "phone_get_state") return success({ control_lease: { active: leaseActive } });
        return success({ ok: true });
      },
    };
    const log = vi.fn();

    const result = await verifyLivePhone(definitions(), caller, log);

    expect(result).toMatchObject({
      catalogSize: 38,
      capabilityChecks: ["accessibility", "screen_capture", "ocr", "notification_access"],
      bridgeResult: "UGhvbmVNQ1A=",
      leaseReleased: true,
    });
    expect(calls).toContainEqual({
      name: "phone_call_js_api",
      args: {
        lease_id: "lease-1",
        api: "base64.encode",
        arguments: ["PhoneMCP"],
        result_mode: "json",
      },
    });
    expect(calls.at(-1)?.name).toBe("phone_get_state");
    expect(log).toHaveBeenCalledWith("PASS cleanup lease_released");
  });
});

function definitions(): McpToolDefinition[] {
  const required = new Set([
    "phone_get_capabilities", "phone_get_state", "phone_get_transport_status", "phone_get_permissions",
    "phone_list_js_apis", "phone_wait_for", "phone_list_apps", "phone_get_app_info", "phone_get_jobs",
    "phone_get_performance_metrics",
    "phone_ui_snapshot", "phone_capture_screen", "phone_ocr_read", "phone_get_notifications",
    "phone_toast", "phone_session_control", "phone_call_js_api",
  ]);
  const names = [...required, ...Array.from({ length: 38 - required.size }, (_, index) => `phone_placeholder_${index}`)];
  return names.map((name) => ({
    name,
    description: name,
    inputSchema: { type: "object", properties: {}, additionalProperties: true },
    annotations: { readOnlyHint: name !== "phone_toast" && name !== "phone_session_control" && name !== "phone_call_js_api" },
  }));
}

function success(structuredContent: Record<string, unknown>) {
  return { content: [{ type: "text" as const, text: JSON.stringify(structuredContent) }], structuredContent, isError: false };
}

function failure(code: string) {
  return { content: [{ type: "text" as const, text: `${code}: expected failure` }], isError: true };
}
