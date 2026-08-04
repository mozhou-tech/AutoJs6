# PhoneMCP MCP tool testing

Use a layered test strategy so protocol, adapter, transport, and Android runtime failures remain distinguishable. The standalone harness is `phone-mcp-agent-tests/`; keep it outside the Android Gradle modules.

## Test matrix

| Layer | Environment | Purpose |
| --- | --- | --- |
| Android JVM contract | Gradle/JUnit | Validate tool schemas, annotations, argument rejection, JavaScript API catalog, and bridge injection defenses. |
| Pi adapter unit | Node/Vitest | Preserve names and JSON Schema, map annotations to execution modes, forward cancellation, map MCP content, and surface `isError`. |
| In-memory MCP integration | Node/Vitest | Discover and invoke a real MCP server without Android, network, token, or model credentials. |
| Live discovery | Emulator/device | Validate authentication, Streamable HTTP transport, complete catalog, schemas, and annotations. |
| Read-only smoke | Emulator/device | Exercise harmless state, capability, and permission calls. |
| Controlled write | Emulator/device | Validate lease enforcement and one reversible control action. |
| Pi Agent end-to-end | Optional model runtime | Validate tool selection only after deterministic layers pass; keep model evaluation separate from protocol correctness. |

Canonical implementation and tests:

- `app/src/main/java/org/autojs/autojs/mcp/PhoneMcpModels.kt`
- `app/src/main/java/org/autojs/autojs/mcp/PhoneToolExecutor.kt`
- `app/src/main/java/org/autojs/autojs/mcp/PhoneJsApiCatalog.kt`
- `app/src/test/java/org/autojs/autojs/mcp/PhoneMcpProtocolTest.kt`
- `app/src/test/java/org/autojs/autojs/mcp/PhoneJsApiCatalogTest.kt`
- `app/src/test/java/org/autojs/autojs/mcp/PhoneJsApiBridgeTest.kt`
- `phone-mcp-agent-tests/src/` and `phone-mcp-agent-tests/test/`

## 1. Run deterministic tests

From the repository root, run Android-side MCP tests:

```bash
./gradlew :app:testAppDebugUnitTest \
  --tests 'org.autojs.autojs.mcp.PhoneMcpProtocolTest' \
  --tests 'org.autojs.autojs.mcp.PhoneJsApiCatalogTest' \
  --tests 'org.autojs.autojs.mcp.PhoneJsApiBridgeTest'
```

Then test and build the standalone Pi Agent component:

```bash
cd phone-mcp-agent-tests
npm ci
npm test
npm run build
```

The deterministic suite must verify:

- Every advertised tool has a `phone_` name, object input schema, and all four MCP annotations.
- Pi preserves tool names and schemas exactly.
- Read-only tools use parallel Pi execution; all other tools use sequential execution.
- Text, image, and structured content survive adaptation.
- Abort signals and timeouts reach the MCP client.
- MCP `isError` becomes a Pi tool execution error with the server message.
- The JavaScript bridge rejects prototype traversal and source injection.
- Tests use in-memory transports and fake deterministic data; they need no Android device, token, network service, API key, or model.

When adding or removing a tool, update the protocol assertion and live smoke catalog expectation together. Derive expectations from `PhoneToolSpecs.all`; do not silently accept a missing tool merely by weakening the count.

## 2. Prepare a live emulator test

1. Start the Android emulator and confirm it appears in `adb devices`.
2. Install the intended PhoneMCP build.
3. In PhoneMCP, enable local-only debugging and start the MCP server.
4. Copy the pairing token from the PhoneMCP settings page.
5. Forward the local-only port and set credentials only in the current shell:

```bash
adb forward tcp:8765 tcp:8765
export PHONE_MCP_URL='http://127.0.0.1:8765/mcp'
export PHONE_MCP_TOKEN='<pairing token>'
export PHONE_MCP_TIMEOUT_MS='30000'
cd phone-mcp-agent-tests
```

Never commit `.env` files or print the token. If multiple devices are attached, select the intended serial with `adb -s <serial> forward tcp:8765 tcp:8765`.

## 3. Discover tools and run read-only smoke

```bash
npm run list -- --json
npm run smoke
npm run verify
```

`list` validates live discovery and Pi adaptation. Inspect names, descriptions, schemas, and execution modes. `smoke` calls only `phone_get_capabilities`, `phone_get_state`, and `phone_get_permissions`; it must not modify the device.

`verify` additionally exercises parameterized read tools, server-side schema rejection, invalid-lease rejection, a short control lease, the side-effect-free `base64.encode` JavaScript bridge, and lease cleanup. Run it after changing a tool schema, executor dispatch, session control, or the JavaScript bridge.

Test a parameterized read-only tool separately when its schema changes:

```bash
npm run call -- --tool phone_list_js_apis \
  --args '{"query":"getClip","limit":10}'
```

Prefer `phone_list_js_apis` before testing `phone_call_js_api`. Verify `preferred_tool`, `access`, and `requires` metadata rather than assuming every catalog entry is safe or available.

## 4. Test lease enforcement and the JavaScript bridge

First make a negative call to a control tool without `lease_id` and require an authentication or lease error. Then acquire a short lease, perform one harmless action, and release it even if the action fails.

The following bridge test uses `base64.encode`, which has no device-side mutation, although the generic bridge is conservatively annotated as destructive. It requires `jq` only to carry the returned lease ID safely:

```bash
lease_json=$(npm run --silent call -- \
  --tool phone_session_control \
  --args '{"action":"acquire","ttl_seconds":60}')
lease_id=$(printf '%s' "$lease_json" | jq -r '.structuredContent.lease_id')

cleanup_lease() {
  release_args=$(jq -nc --arg lease_id "$lease_id" \
    '{action:"release",lease_id:$lease_id}')
  npm run --silent call -- \
    --tool phone_session_control --args "$release_args" >/dev/null || true
}
trap cleanup_lease EXIT

bridge_args=$(jq -nc --arg lease_id "$lease_id" \
  '{lease_id:$lease_id,api:"base64.encode",arguments:["PhoneMCP"],result_mode:"json"}')
npm run call -- --tool phone_call_js_api --args "$bridge_args"
```

Use the generic bridge only to test bridge behavior or when no dedicated tool exists. Functional tests should prefer the dedicated tool declared by `preferred_tool`, because it has tighter schema and risk annotations.

For a controlled write test, choose a reversible visible action such as a short toast or vibration only after the user authorizes device mutation. Never use file deletion, app force-stop, shell, message sending, purchases, account changes, or sensitive clipboard reads as smoke tests.

## 5. Run an OpenAI-compatible Pi Agent smoke test

After deterministic and live protocol tests pass, validate model tool selection separately:

```bash
export OPENAI_COMPAT_BASE_URL='https://provider.example/v1'
export OPENAI_COMPAT_MODEL='model-id'
export OPENAI_COMPAT_API_KEY='<API key>'
npm run agent:smoke
```

The command exposes only four read-only tools and requires calls to `phone_get_capabilities` and `phone_get_permissions`. A pass proves endpoint compatibility, Pi streaming, tool-call generation, MCP execution, tool-result replay, and the final model response. It does not prove correctness of control tools; use `verify` for deterministic control-path coverage.

## 6. Diagnose failures by layer

| Symptom | Check |
| --- | --- |
| `PHONE_MCP_TOKEN is required` or HTTP unauthorized | Set the current pairing token; confirm it was not revoked or copied with whitespace. |
| Connection refused or timeout | Confirm the app-side service is running, inspect `adb forward --list`, verify URL/port, then increase `PHONE_MCP_TIMEOUT_MS` only if the server is reachable. |
| Catalog count mismatch | Compare `PhoneToolSpecs.all`, `PhoneMcpProtocolTest`, and `phone-mcp-agent-tests/src/cli.ts`; update deliberate catalog changes in all three. |
| Schema validation error | Read the live `tools/list` schema; do not guess argument names or types. Additional properties are rejected. |
| Lease missing, expired, busy, or denied | Acquire or renew through `phone_session_control`; pass the exact returned ID; release stale client leases or wait for expiry. |
| Accessibility, screenshot, OCR, notification, or write-settings error | Inspect `phone_get_capabilities` and `phone_get_permissions`, then enable only the required Android capability. |
| Pi adapter unit failure | Compare raw MCP result and annotations with `src/pi-tools.ts`; keep protocol errors distinct from model behavior. |
| Model chose the wrong tool | Confirm deterministic tests pass, then record the prompt, advertised tool definitions, tool-call trace, and result as a separate agent evaluation. |

## 7. Record evidence

Report commands, build variant, emulator serial, server URL without credentials, tool catalog size, tools called, arguments with secrets removed, and pass/fail output. Do not record pairing tokens, clipboard contents, notification bodies, screenshots containing private data, or other credentials.
