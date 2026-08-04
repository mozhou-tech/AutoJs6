# PhoneMCP Pi Agent 测试框架

这是一个独立于 Android/Gradle 工程的 TypeScript 组件。它连接运行中的 PhoneMCP
Streamable HTTP 端点，通过 `tools/list` 动态发现工具，并将每个 MCP 工具转换成
`@earendil-works/pi-agent-core` 的 `AgentTool`：

- 原样保留工具名称、说明和 JSON Schema。
- 只读工具使用 Pi 并行执行模式；控制工具强制顺序执行。
- MCP 文本与图片映射成 Pi 工具结果。
- MCP `isError` 转换成异常，让 Pi Agent 生成标准错误工具结果。
- 配对令牌只从环境变量读取，不写入代码、参数或日志。

## 本地测试

需要 Node.js 22.19 或更高版本：

```bash
cd phone-mcp-agent-tests
npm install
npm test
npm run build
```

默认测试使用进程内 MCP Transport，不需要 Android 设备、PhoneMCP 服务或模型 API Key。

## 连接 Android 虚拟机

1. 在 PhoneMCP 中开启“仅本地调试”并启动 MCP 服务。
2. 转发端口并通过环境变量提供配对令牌：

```bash
adb forward tcp:8765 tcp:8765
export PHONE_MCP_TOKEN='<PhoneMCP 设置页复制的配对令牌>'
export PHONE_MCP_URL='http://127.0.0.1:8765/mcp'
```

列出适配后的 Pi Agent 工具：

```bash
npm run list
```

运行不会修改设备的只读 smoke 测试：

```bash
npm run smoke
```

调用指定工具（控制类工具仍须先获取 `phone_session_control` 租约）：

```bash
npm run call -- --tool phone_get_state --args '{}'
```

可配置 `PHONE_MCP_TIMEOUT_MS` 调整默认的 30 秒请求超时。

## 在 Pi Agent 中使用

业务代码可直接复用适配器：

```ts
import type { Agent } from "@earendil-works/pi-agent-core";
import { PhoneMcpClient, createPhoneMcpAgentTools } from "./dist/index.js";

async function attachPhoneMcpTools(agent: Agent) {
  const phone = new PhoneMcpClient({
    url: process.env.PHONE_MCP_URL!,
    token: process.env.PHONE_MCP_TOKEN!,
  });
  await phone.connect();
  agent.state.tools = createPhoneMcpAgentTools(await phone.listTools(), phone);
  return phone;
}
```

测试框架不内置模型供应商或密钥；模型选择与认证由调用它的 Pi Agent 应用负责。
