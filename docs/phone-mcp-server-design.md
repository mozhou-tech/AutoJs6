# Phone MCP Server 设计

## 1. 目标与范围

在 AutoJs6 内提供一个面向 Agent 的手机控制 MCP Server，使远端 Agent 能够：

- 感知手机当前状态、屏幕内容和无障碍节点树。
- 通过语义控件、坐标手势、系统按键和 Android Intent 操作手机。
- 管理应用、通知、剪贴板、文件和常用系统设置。
- 使用内置 PP-OCRv6 识别无障碍节点树无法覆盖的文字。
- 在用户显式授权后执行脚本、Shell、安装卸载等高权限操作。
- 通过内嵌 Tailscale `tsnet` 接入自建 Headscale 网络，从受信任设备远程访问。

本设计优先覆盖通用原子能力，再提供减少网络往返的组合工具。第一版在 AutoJs6
内集成一个基于 Go `tailscale.com/tsnet` 的薄 AAR 桥接层，使 MCP 服务成为
Headscale 网络中的独立节点，但不接管整台手机的网络流量。网络成员身份不能作为
唯一的应用层认证依据。

所有 MCP 工具统一使用 `phone_` 前缀和 `snake_case` 命名。

### 1.1 当前实现状态（2026-08-03）

第一版已经落地：Android 端提供 MCP JSON-RPC、30 个 `phone_` 工具、独占控制租约、
前台服务、开机恢复和应用内设置入口；Go 桥接层基于 `tailscale.com v1.102.0` 与
`gomobile` 生成 `phone-tailnet.aar`，覆盖 arm64-v8a 和 armeabi-v7a；为控制内置产物体积，
不包含 x86 和 x86_64 模拟器 ABI。
设置入口位于“设置 → 手机 MCP 服务”，用户可配置 Headscale HTTPS Server、一次性
预授权密钥、节点名、Tailnet 端口和本地调试端口。

当前传输实现支持 MCP `2025-11-25` 与 `2025-06-18` 的 JSON-RPC 请求/响应子集，采用
无状态 HTTP POST；第一版不启用 SSE。Go 层直接终止 HTTP、执行请求限制与应用层鉴权，
再通过 gomobile `Handler` 回调 Kotlin MCP 协议层，没有额外的回环反向代理跳数。

## 2. 总体架构

```text
MCP Client / Agent
        |
        | MCP Streamable HTTP
        | Headscale/Tailscale encrypted network
        v
Embedded tsnet node (Go AAR)
        |
        | bounded HTTP + Bearer/Origin checks
        | gomobile Handler callback
        v
AutoJs6 Phone MCP Foreground Service (Kotlin)
        |
        +-- authentication and session lease
        +-- serialized command dispatcher
        +-- accessibility and screen capture
        +-- PP-OCRv6
        +-- app, file and system adapters
        +-- Android permission and capability checks
```

AutoJs6 设置页由用户填写 Headscale 控制服务器地址，例如
`https://headscale.example.com`，并通过一次性 Headscale pre-auth key 完成首次注册。
MCP Client 使用 Headscale 分配给 AutoJs6 内嵌节点的 IP 或名称访问，例如：

```text
http://100.64.0.12:8765/mcp
```

Headscale 地址和手机 MCP 地址是两个不同概念：前者用于内嵌节点注册和组网，后者是
Agent 调用工具的业务端点。内嵌节点只承载 AutoJs6 MCP 流量，不提供整机 VPN。
手机上已安装的官方 Tailscale App 可以继续使用，两者在 Headscale 中表现为两个
独立节点。

### 2.1 使用与连接

1. 打开“设置 → 手机 MCP 服务”。
2. 正式使用时填写 Headscale HTTPS Server、一次性预授权密钥和节点名；调试时可开启
   “仅本地调试”。
3. 复制应用层配对令牌并启用服务。正式 MCP URL 为
   `http://<tailnet-ip-or-name>:8765/mcp`，请求携带
   `Authorization: Bearer <pairing-token>`。
4. 本地验证可执行 `adb forward tcp:8765 tcp:8765`，随后连接
   `http://127.0.0.1:8765/mcp`。
5. Agent 首先调用 `phone_get_capabilities` 与 `phone_get_permissions`；涉及写操作时先通过
   `phone_session_control` 获取租约，并在后续参数中携带 `lease_id`。

无障碍、通知读取等 Android 能力仍需用户在系统设置中单独授权。Headscale Server 与
配对令牌承担不同职责：前者提供私有网络和节点身份，后者提供 MCP 应用层认证。

MCP Server 和内嵌 tsnet 节点由同一个前台服务管理。常驻通知必须显示 Headscale
连接状态、当前连接方、会话剩余时间和“立即停止”入口。服务关闭时必须关闭 tsnet
监听器和本地 MCP Server，不得继续接受新请求。

### 2.2 Tailscale 集成决策

采用 Go `tailscale.com/tsnet` 作为嵌入式网络入口，并用 `gomobile bind` 生成项目
自有 AAR。不要依赖 Tailscale Android 客户端内部 `libtailscale` API，也不复制完整
Android VPN 客户端实现。

选择 tsnet 的原因：

- MCP 只需要把 AutoJs6 的一个服务暴露到 Tailnet，不需要接管整机路由和 DNS。
- tsnet 使用用户态网络栈，可以为 MCP 服务创建独立的 Tailnet IP 和节点身份。
- 不占用 Android 的 `VpnService` 槽位，不与用户现有 VPN 直接冲突。
- `tsnet.Server.ControlURL` 可以指向自建 Headscale 控制服务器。
- 可以通过 tsnet LocalAPI `WhoIs` 获取入站连接对应的节点身份。

不采用完整 `libtailscale` 的原因：

- 它面向官方 Android VPN 客户端内部实现，不是稳定发布的 Maven Android SDK。
- 需要管理 TUN、路由、DNS、Always-on VPN 和 Android VPN 生命周期。
- 会与其他 VPN 互斥，超出 Phone MCP Server 的必要范围。
- 内部 gomobile API 的升级兼容成本和测试范围显著更大。

### 2.3 AAR 模块边界

新增独立的 Go 包装模块 `phone-tailnet`，内部持有 `tsnet.Server`，只向 Kotlin
暴露适合 gomobile 的小型稳定接口：

```text
newBridge(): Bridge
Bridge.start(configJson, Handler)
Bridge.stop()
Bridge.statusJSON(): String
Handler.handle(requestJson, peerJson): responseJson
```

不要直接向 Kotlin 暴露 Go 的 `net.Listener`、`context.Context`、channel 或复杂结构。
Go 层负责 Tailnet/回环 listener、WhoIs、HTTP 限制、Bearer 与 Origin 校验；Kotlin 层
负责 MCP 协议、工具级授权、控制租约和用户界面。

首批配置结构：

```json
{
  "control_url": "https://headscale.example.com",
  "hostname": "phone-mcp-pixel9",
  "tailnet_port": 8765,
  "local_port": 8765,
  "state_dir": "<android-private-no-backup-dir>",
  "auth_key": "<one-time-key>",
  "pairing_token": "<application-token>",
  "local_only": false
}
```

`control_url` 只接受 HTTPS URL，不允许查询参数、用户信息或非根路径。生产环境默认
要求系统信任的 TLS 证书；调试环境的自签名证书必须显式导入系统信任库，不提供全局
关闭证书校验的选项。

### 2.4 节点注册与状态存储

第一版优先支持一次性、短有效期的 Headscale pre-auth key，可通过二维码或粘贴导入。
密钥成功使用后立即从配置界面清除，不写入日志或审计记录；注册前的短暂持久化内容和
应用层配对令牌使用 Android Keystore AES-GCM 加密。
后续可以支持交互式认证 URL，但不能依赖解析 tsnet 日志来获取认证地址，Go 桥接层
必须通过结构化状态返回。

tsnet 状态包含节点私钥，必须：

- 存放在 Android `noBackupFilesDir`，禁止进入系统云备份。
- Go `ipn.StateStore` 通过 gomobile 回调 Kotlin；Kotlin 使用 Android Keystore AES-GCM
  加密每个状态值，再以原子文件写入 `noBackupFilesDir/phone-tailnet-state`。
- 使用应用私有文件权限，不允许脚本运行时和普通文件工具读取。
- 日志中隐藏 pre-auth key、认证 URL、节点私钥和完整节点状态。
- 内嵌 tsnet 禁用向 Tailscale logtail 上传诊断日志，并丢弃可能包含认证 URL 的
  `UserLogf`。
- 设置页提供“重置 Tailnet 节点身份”，经用户确认、停止服务后销毁加密状态；
  Headscale 服务端删除节点是独立操作。

Headscale 注册密钥不打包进 APK。自动化部署时由受信任的管理端按设备生成一次性密钥。

### 2.5 Tailnet listener 与请求分发

Go 层使用 `tsnet.Server.Listen("tcp", ":8765")` 监听 Tailnet，并额外提供只绑定
`127.0.0.1` 的同协议调试入口。HTTP 层满足：

- 第一版接受无状态 MCP HTTP POST，明确拒绝 GET/SSE。
- 限制请求体、请求头、读写和空闲超时；同时最多处理 8 个请求，过载返回 HTTP 429。
- `/healthz` 与 `/mcp` 分离，健康检查不返回敏感状态。
- 请求进入 Kotlin 前完成 Bearer、Origin、Content-Type 和 JSON 有效性校验。
- Go 层停止时主动关闭 listener、现有连接和 tsnet 节点。

Go 层通过 LocalAPI `WhoIs` 获取来源节点，并通过进程内 Handler 参数传给 Kotlin：

```text
transport
source_address
node_id
node_name
user_login
```

身份数据不经过外部 HTTP Header，客户端无法注入或覆盖。回环调试入口只提供来源地址，
不会伪造 Tailnet 节点身份。WhoIs 是认证输入之一，不能替代应用层配对凭证。

### 2.6 生命周期与后台运行

tsnet 节点和 MCP Server 共用一个明确由用户开启的前台服务生命周期：

1. 读取并解密节点状态。
2. 启动 `tsnet.Server` 并等待达到可用状态。
3. 启动 Tailnet MCP listener 和本地调试 listener。
4. 更新常驻通知中的节点名、Tailnet IP、直连/中继状态和客户端数量。
5. 用户停止、登出或服务销毁时按相反顺序关闭资源。

不要以永久 WakeLock 保活。必须测试 Doze、锁屏、后台限制、网络切换和进程重建。
若系统终止进程，重启后使用持久节点状态恢复，不重新消耗 pre-auth key。

### 2.7 依赖和构建

- 固定 Tailscale 源码 release 或提交，不跟随 `main` 浮动构建。
- Go 包装层维护独立 API 版本，升级 Tailscale 时执行兼容和网络回归测试。
- 通过可重复的 Gradle/Go 构建任务生成 AAR，并校验产物 SHA-256。
- 支持项目现有 ABI；发布时优先使用 App Bundle 或 ABI split 控制安装体积。
- 保留 Tailscale 及传递依赖的许可证、NOTICE 和源码版本信息。
- Release 构建保留必要 native 调试符号映射，便于定位 Go native 崩溃。

第一阶段技术验证必须先测量每个 ABI 的体积增量、空闲内存、启动耗时、常驻电量和
网络恢复时间，再决定是否默认随主 APK 发布或作为独立产品变体发布。

## 3. 设计原则

### 3.1 感知优先级

Agent 按以下顺序理解页面：

1. 无障碍节点树和节点语义。
2. 截图及图像区域。
3. PP-OCRv6 文字识别。
4. 坐标和视觉特征兜底。

语义节点操作比坐标点击更稳定。坐标操作不能被禁止，因为游戏、Canvas、WebView、
视频和自绘界面可能没有可用节点。

### 3.2 单写者模型

同一时刻只允许一个持有控制租约的 Agent 执行写操作。只读请求可以并发；点击、
输入、应用控制和文件写入必须进入一个串行命令队列。会话断开或租约超时后自动释放。

### 3.3 状态版本与前置条件

每次状态和 UI 快照包含 `state_version` 或 `snapshot_id`。Agent 使用节点执行操作时
必须携带快照 ID。页面已变化时返回 `STALE_SNAPSHOT`，不得在未知页面上继续点击。

写操作可以携带前置条件，例如前台包名、Activity、节点存在、文件哈希或屏幕方向。
前置条件不满足时不执行操作。

### 3.4 完整覆盖与受控逃生口

常规任务使用类型明确的工具。`phone_run_script` 和 `phone_shell_exec` 用于覆盖未预见
场景，但默认关闭，并受到能力检测、超时、目录限制、用户确认和审计约束。

## 4. 通用数据契约

### 4.1 成功结果

工具返回 MCP `structuredContent`，并提供简洁文本摘要。统一结构为：

```json
{
  "ok": true,
  "request_id": "req_123",
  "device_id": "pixel-9",
  "state_version": 381,
  "data": {},
  "warnings": [],
  "duration_ms": 187
}
```

`phone_capture_screen` 和 `phone_capture_context` 还应直接返回 MCP `image` 内容。大文件和长日志返回资源 URI、
游标或下载句柄，避免把大段 Base64 或无界文本放入模型上下文。

### 4.2 错误结果

业务错误放在工具结果中，不升级为 MCP 传输错误：

```json
{
  "ok": false,
  "request_id": "req_123",
  "error": {
    "code": "ACCESSIBILITY_DISABLED",
    "message": "无障碍服务尚未启用",
    "recoverable": true,
    "suggested_action": {
      "tool": "phone_app_control",
      "arguments": {
        "action": "open_accessibility_settings"
      }
    }
  }
}
```

首批统一错误码：

- `AUTHENTICATION_REQUIRED`
- `USER_CONFIRMATION_REQUIRED`
- `PERMISSION_REQUIRED`
- `CAPABILITY_UNAVAILABLE`
- `ACCESSIBILITY_DISABLED`
- `SCREEN_CAPTURE_DISABLED`
- `TAILNET_NOT_CONFIGURED`
- `TAILNET_AUTH_REQUIRED`
- `TAILNET_OFFLINE`
- `HEADSCALE_UNREACHABLE`
- `STALE_SNAPSHOT`
- `TARGET_NOT_FOUND`
- `AMBIGUOUS_TARGET`
- `DEVICE_LOCKED`
- `SESSION_BUSY`
- `TIMEOUT`
- `APP_NOT_INSTALLED`
- `PRIVILEGE_REQUIRED`
- `POLICY_DENIED`
- `INVALID_PRECONDITION`

错误信息应给出可以执行的下一步，但不得暴露密钥、内部堆栈或其他客户端信息。

### 4.3 列表与长任务

列表工具统一接受 `limit` 和 `cursor`，返回 `items`、`has_more` 和 `next_cursor`。
默认 `limit` 为 20，服务端设定最大值。

安装、录屏、长脚本等操作返回 `job_id`。Agent 通过 `phone_get_jobs` 查询，通过
`phone_cancel_job` 取消，不维持无上限的 HTTP 请求。

## 5. 工具目录

### 5.1 连接与能力发现

#### `phone_get_capabilities`

返回设备、系统和服务的真实能力。Agent 不得猜测 Root、Shizuku、通知读取、截图、
静默安装或文件访问是否可用。

主要输出：

```json
{
  "android_version": 16,
  "screen": {
    "width": 1080,
    "height": 2424,
    "density": 2.75,
    "rotation": 0
  },
  "capabilities": {
    "accessibility": true,
    "screen_capture": true,
    "ocr": true,
    "visual_context": true,
    "notification_access": false,
    "root": false,
    "shizuku": false,
    "shell": "app",
    "file_scope": "shared_storage"
  }
}
```

MCP annotations：`readOnlyHint=true`、`destructiveHint=false`、
`idempotentHint=true`、`openWorldHint=false`。

#### `phone_get_state`

返回亮屏、锁屏、前台包名、Activity、窗口、屏幕方向、网络、电量、音量、输入法和
当前控制会话。支持 `fields` 过滤，避免每次返回全部信息。

#### `phone_get_transport_status`

返回 MCP 当前传输层状态，包括内嵌 tsnet 是否启动、Headscale 控制服务器的脱敏
主机名、节点名、Tailnet IP、登录状态、网络映射更新时间、当前连接数和最近一次脱敏
错误。若底层能够可靠判断，还可以返回连接使用直连还是 DERP 中继。

该工具不得返回 pre-auth key、节点私钥、认证 URL 中的敏感参数或其他节点的完整
网络映射。它是只读诊断工具，不提供修改 Headscale 地址、重新认证或登出动作。

#### `phone_get_permissions`

返回 AutoJs6 已有的 Android 普通权限、运行时权限和特殊授权，以及缺失能力对应的
设置入口。

#### `phone_session_control`

`action` 支持 `acquire`、`renew`、`release`。获取成功后返回短期 `lease_id` 和过期
时间。所有写工具要求同一连接持有有效租约。

### 5.2 屏幕理解

#### `phone_capture_screen`

获取当前截图。支持：

- `region`: 可选裁剪区域。
- `max_width` / `max_height`: 下采样限制。
- `format`: `png`、`jpeg` 或 `webp`。
- `quality`: 有损格式质量。
- `include_metadata`: 是否返回前台应用、方向和时间戳。

输出必须包含原始屏幕尺寸、返回图像尺寸、裁剪区域、旋转方向和截图时间。

#### `phone_capture_context`

为远端 VLM/Agent 一次返回同一采集窗口内的屏幕图像、物理屏幕元数据、无障碍节点树
和 PP-OCRv6 结果。截图和 OCR 必须共享同一张原始位图，避免连续截屏期间页面变化造成
图像与文字错位；节点树返回可复用的 `snapshot_id`，供后续 `phone_ui_action` 精确操作。

支持 `format`、`quality`、`max_width`、`max_nodes`、`visible_only` 和
`min_confidence`。图像可以按 `max_width` 下采样以节省上下文，但节点边界和 OCR 边界
始终采用原始物理屏幕坐标。返回的 `screen.coordinate_space` 明确标记为
`physical_screen`，并同时提供原始尺寸和返回图像尺寸，便于 VLM 做坐标映射。

该工具仅负责可靠采集，不在手机端绑定特定 VLM，也不直接执行任何动作。Agent 应优先
使用无障碍节点语义，其次使用 OCR，最后才通过视觉推理选择坐标；付款、授权、删除、
发送消息和安装等高风险操作仍必须经过现有租约、权限和用户确认策略。

#### `phone_ui_snapshot`

返回当前窗口和无障碍节点树。支持限制深度、仅返回可见节点，以及是否同时附带截图。
每个节点至少包含：

```json
{
  "node_id": "node_17",
  "parent_id": "node_3",
  "text": "登录",
  "resource_id": "com.example:id/login",
  "content_description": null,
  "class_name": "android.widget.Button",
  "bounds": [720, 1900, 1030, 2020],
  "clickable": true,
  "scrollable": false,
  "editable": false,
  "enabled": true,
  "visible": true
}
```

节点 ID 只在对应 `snapshot_id` 内有效。

#### `phone_ui_find`

使用组合选择器查找节点，支持：

- `text`、`resource_id`、`content_description`、`class_name`。
- `clickable`、`scrollable`、`editable`、`enabled`、`visible`。
- `bounds` 或父节点范围。
- `match`: `exact`、`contains`、`regex`。
- `limit` 和 `cursor`。

默认返回所有候选的节点 ID、边界和最小上下文。多个匹配不能自动选取第一个执行。

#### `phone_ocr_read`

调用内置 PP-OCRv6，对全屏、截图句柄或指定区域识别。支持文本过滤和置信度阈值，
返回文字、置信度、四边形及轴对齐边界。OCR 坐标必须转换到当前物理屏幕坐标系。

#### `phone_wait_for`

在服务端等待以下条件，避免 Agent 高频轮询：

- 节点出现或消失。
- OCR 文字出现或消失。
- 前台包名或 Activity 改变。
- 屏幕区域发生变化或稳定一段时间。
- 设备亮屏、解锁或网络恢复。

参数包含 `condition`、`timeout_ms`、`stable_for_ms`。成功时返回满足条件的新状态或
快照。

#### `phone_compare_screen`

比较两个截图句柄或当前屏幕与历史截图，返回变化比例和变化边界。用于判断点击是否
生效以及页面是否完成加载。

### 5.3 界面操作

#### `phone_ui_action`

对无障碍节点执行语义操作。`action` 首批支持：

- `click`
- `long_click`
- `set_text`
- `clear_text`
- `focus`
- `scroll_forward`
- `scroll_backward`
- `select`
- `copy`
- `paste`

推荐使用快照节点：

```json
{
  "action": "click",
  "target": {
    "snapshot_id": "snap_01892",
    "node_id": "node_17"
  },
  "timeout_ms": 5000
}
```

也可以直接提供与 `phone_ui_find` 相同的选择器。如果匹配多个节点，返回
`AMBIGUOUS_TARGET` 和候选列表，不执行动作。

#### `phone_gesture`

执行坐标点击、长按、滑动、拖拽和多点触控。坐标支持物理像素或归一化 `0..1`。
输入携带预期屏幕尺寸和方向；不一致时默认拒绝，避免旋转后误触。

#### `phone_global_action`

支持 `back`、`home`、`recents`、`notifications`、`quick_settings`、`lock_screen` 和
`power_dialog`。服务端只暴露当前系统与无障碍服务实际支持的动作。

#### `phone_input_text`

向当前输入目标写入文本，支持 `replace`、`append` 和 `insert`。参数可标记
`sensitive=true`，敏感文本不得写入日志、错误信息、截图注释或审计详情。

#### `phone_key_event`

支持音量、媒体、方向、回车、删除等允许列表中的按键。电源键等高风险按键根据设备
能力单独授权。

#### `phone_action_sequence`

串行执行最多 50 个动作、等待和断言，默认 `stop_on_error=true`，总执行时间不超过
两分钟。每一步返回序号、状态、耗时和必要的恢复信息。失败时返回最后已完成步骤以及
最新屏幕/快照句柄。

组合工具不能绕过其子操作的权限、确认和审计策略。

### 5.4 应用和 Intent

#### `phone_list_apps`

分页列出已安装应用，支持按名称、包名、用户/系统应用和 enabled 状态过滤。

#### `phone_get_app_info`

返回包名、版本、入口 Activity、安装来源、声明权限、已授予权限和可处理的主要
Intent。

#### `phone_app_control`

`action` 支持：

- `launch`
- `bring_to_front`
- `force_stop`
- `clear_from_recents`
- `open_settings`
- `open_permissions`
- `open_accessibility_settings`
- `clear_cache`
- `clear_data`

实际动作取决于普通应用、无障碍、Shizuku、Device Owner 或 Root 能力。`clear_data`
属于破坏性操作，必须逐次确认。

#### `phone_open_uri`

打开 HTTPS URL、Deep Link 或受控 Android Intent。显式 Intent 的 package、component、
action、category、data 和 extras 必须分别校验；禁止接受拼接后的原始 Shell 命令。

#### `phone_install_app` / `phone_uninstall_app`

安装来源只能是已上传文件、受信任资源 URI 或允许域名的 HTTPS URL。安装前返回包名、
签名、版本和权限变化供确认。卸载、降级、签名变化和覆盖安装按 L3 风险处理。

#### `phone_manage_permission`

默认只打开对应权限设置页；仅在 Shizuku、Device Owner 或 Root 能力可用且策略允许时
直接授予或撤销权限。

### 5.5 系统、通知和剪贴板

#### `phone_device_control`

控制亮屏、锁屏、旋转、亮度、音量和勿扰模式。每个 action 都必须通过能力发现明确
声明是否支持，不允许静默降级为近似操作。

#### `phone_get_setting` / `phone_set_setting`

读取和修改允许列表内的系统设置。默认不提供任意 Settings Provider 键值写入。
修改无线调试、开发者选项、安全设置和辅助功能服务属于特权操作。

#### `phone_get_network_state` / `phone_control_network`

返回 Wi-Fi、移动网络、系统 VPN 和底层网络可见信息。内嵌 tsnet 的详细状态由
`phone_get_transport_status` 返回。普通权限下，网络控制工具打开对应系统面板；只有
明确具备特权能力时才直接切换。远端工具不能修改 Headscale Server 或节点凭证。

#### `phone_get_notifications` / `phone_notification_action`

通知读取依赖 Notification Listener 授权。动作支持点击通知、关闭通知或触发指定
action。一次性验证码和敏感通知内容默认脱敏，并可通过本地策略完全禁止远端读取。

#### `phone_get_clipboard` / `phone_set_clipboard`

读取、写入或清空剪贴板。读取属于敏感只读操作；返回内容设置长度上限，且默认不在
审计日志记录明文。

### 5.6 文件与媒体

#### `phone_list_files` / `phone_read_file` / `phone_write_file`

文件工具遵循 Android Scoped Storage 和用户授权范围。所有路径必须规范化，拒绝目录
穿越。文本读取支持编码和最大长度；二进制内容使用资源或上传下载句柄。

覆盖文件时可提供 `expected_sha256`，文件已变化则拒绝覆盖。

#### `phone_manage_file`

支持 `mkdir`、`copy`、`move`、`rename` 和 `delete`。删除默认进入可恢复区域；永久
删除、递归删除和覆盖目标按破坏性操作处理。

#### `phone_upload_file` / `phone_download_file`

使用流式或分块传输，校验大小和 SHA-256。临时文件必须设置配额和过期清理机制。

#### `phone_share_file`

通过 Android Sharesheet 分享指定文件。Agent 可以准备分享内容，但发送到外部应用前
按目标和内容风险决定是否需要手机端确认。

#### `phone_query_media`

通过 MediaStore 分页查询图片、视频和音频，支持时间、类型、相册和名称过滤；默认只
返回元数据和缩略图句柄。

### 5.7 可选个人数据模块

以下工具不进入默认首发能力，必须单独授权：

- `phone_query_contacts`
- `phone_create_contact`
- `phone_query_sms`
- `phone_send_sms`
- `phone_make_call`
- `phone_query_calendar`
- `phone_create_calendar_event`
- `phone_get_location`

短信发送和通话默认只打开已经填充内容的系统编辑页面，由用户完成最终发送。静默发送、
直接拨号、联系人读取、精确位置和日历写入至少属于 L2 风险。

### 5.8 诊断、任务和高级控制

#### `phone_query_logs`

查询 AutoJs6 日志和设备权限允许的系统日志。必须要求时间范围、级别、标签或包名中的
至少一个过滤条件，并设置返回条数上限。日志中的令牌、密码和敏感输入需要脱敏。

#### `phone_get_processes`

返回系统允许查看的进程、前台状态、PID 和基础资源信息，不承诺普通 Android 应用
无法获得的跨应用数据。

#### `phone_get_jobs` / `phone_cancel_job`

查询或取消安装、录屏、上传、下载、长脚本等异步任务。列表支持状态过滤和分页。

#### `phone_run_script`

运行受限 AutoJs6 脚本。默认只运行本地已签名、用户选择或显式批准的脚本。内联脚本
属于高级模式，限制运行时间、输出大小、文件范围、网络权限和并发数。

#### `phone_shell_exec`

执行 Shell 命令，是默认关闭的 L3 逃生口。输入采用 `command` 和 `args[]`，不得把
未经校验的字符串拼接给 Shell。结果必须声明执行身份：`app`、`adb`、`shizuku` 或
`root`。设置超时、输出上限、命令允许/拒绝策略和逐次确认。

## 6. MCP annotations

所有工具声明 `readOnlyHint`、`destructiveHint`、`idempotentHint` 和
`openWorldHint`。这些字段只帮助 Agent 规划，不能代替服务端权限判断。

典型配置：

| 工具类型 | readOnly | destructive | idempotent | openWorld |
| --- | --- | --- | --- | --- |
| 状态、能力、快照、OCR | true | false | true | false |
| 点击、手势、按键 | false | false | false | false |
| 启动应用、设置固定值 | false | false | 视 action 而定 | false |
| 文件写入、安装、发送消息 | false | true | false | true |
| Shell、脚本 | false | true | false | true |

当一个工具的 action 跨越多个风险级别时，工具 annotation 采用最保守值，服务端再按
具体 action 做细粒度判断。

## 7. 认证、授权和确认

### 7.1 网络层

- 内嵌 tsnet 节点通过 Headscale 完成设备组网、路由和传输加密。
- Headscale 策略只允许指定客户端或标签访问 AutoJs6 节点的 MCP TCP 端口。
- Tailnet listener 由 tsnet 创建；本地调试 listener 只监听 `127.0.0.1`。
- Go 层使用 WhoIs 获取来源节点身份，并通过进程内 gomobile 回调传给 MCP 协议层。
- MCP 服务仅在用户开启远程控制时监听；不能因为位于 Tailnet 内就开放匿名访问。
- Headscale 节点身份、网络 ACL 和应用层配对凭证必须同时参与授权。
- 不依赖 Headscale 对 Tailscale SaaS 应用级 Grants 的完全兼容性。

### 7.2 应用层

- 首次连接通过手机端二维码或短码完成配对。
- 配对后使用可撤销、可过期、绑定客户端的凭证。
- 校验 `Origin`、Host 和请求大小，限制连接数和调用速率。
- 每个客户端拥有独立能力集合，服务端逐次鉴权。
- 高风险操作的授权决定由手机端完成，不依赖 MCP annotations。

### 7.3 风险等级

| 等级 | 示例 | 默认策略 |
| --- | --- | --- |
| L0 | 状态、截图、UI 树、OCR | 配对客户端可自动执行 |
| L1 | 点击、滑动、启动应用 | 有效控制租约内自动执行 |
| L2 | 写文件、修改设置、读取个人数据、发送内容 | 会话授权或手机端确认 |
| L3 | 删除数据、卸载、付款、Root Shell、内联脚本 | 手机端逐次确认，默认关闭 |

确认页面显示客户端、工具、目标、关键参数、潜在影响和授权时长。付款、密码、验证码、
恢复出厂设置等场景还需要单独的硬性策略，不能仅依靠通用确认按钮。

## 8. 审计和隐私

记录以下字段：请求 ID、客户端 ID、工具名、风险等级、开始/结束时间、结果、用户确认
状态和被修改资源的标识。默认不记录：

- 密码和输入法敏感文本。
- 配对令牌、Headscale 密钥和其他凭证。
- 剪贴板和通知正文。
- 短信、联系人、位置等个人数据明文。
- 完整截图和文件内容。

用户可以在手机端查看、导出和清除审计记录，并立即吊销客户端。

## 9. 第一版范围

第一版优先实现以下工具：

1. `phone_get_capabilities`
2. `phone_get_state`
3. `phone_get_transport_status`
4. `phone_get_permissions`
5. `phone_session_control`
6. `phone_capture_screen`
7. `phone_capture_context`
8. `phone_ui_snapshot`
9. `phone_ui_find`
10. `phone_ocr_read`
11. `phone_wait_for`
12. `phone_ui_action`
13. `phone_gesture`
14. `phone_global_action`
15. `phone_input_text`
16. `phone_action_sequence`
17. `phone_list_apps`
18. `phone_get_app_info`
19. `phone_app_control`
20. `phone_open_uri`
21. `phone_get_notifications`
22. `phone_notification_action`
23. `phone_get_clipboard`
24. `phone_set_clipboard`
25. `phone_list_files`
26. `phone_read_file`
27. `phone_write_file`
28. `phone_manage_file`
29. `phone_get_jobs`
30. `phone_cancel_job`

第一版还包含内嵌 tsnet AAR、Headscale Server 本地配置、一次性 pre-auth key 注册、
节点状态私有存储、Tailnet/回环 HTTP listener、应用层配对令牌及传输诊断界面。

第一版完成标准：AutoJs6 无需安装外部 Tailscale App 即可注册为独立 Headscale 节点；
Agent 能连接手机、获取独占租约、识别页面、打开任意普通应用、完成常见表单和滚动
交互、等待结果、使用 OCR 兜底、操作通知和限定文件，并能在网络或工具失败后获得明确
的恢复建议。

## 10. 语义流程自动化与固化

Phone MCP 保持模型无关的原子执行层，可在 Agent 侧选配 Midscene 作为语义规划、视觉
定位和流程编排层。生产链路不直接使用依赖 ADB、USB 调试和调试安全设置的
`@midscene/android` 驱动，而是实现一个通过 Headscale 调用 Phone MCP 的
`AbstractInterface` 适配器：

```text
Midscene YAML / TypeScript
        |
        v
Midscene Agent + Phone MCP AbstractInterface
        |
        v
Phone MCP / Headscale / control lease
        |
        v
AutoJs6 Android
```

适配器的 `screenshotBase64()` 和 `size()` 使用 `phone_capture_context` 返回的 MCP
image 与物理屏幕尺寸；动作空间映射到 `phone_ui_action`、`phone_gesture`、
`phone_input_text`、`phone_global_action`、`phone_app_control` 和 `phone_open_uri`。
适配器不能暴露绕过 Phone MCP 策略的 ADB Shell、`pm clear`、强制停止或直接输入注入。

### 10.1 JavaScript 混合编排

复杂流程优先用 JavaScript/TypeScript 显式表达循环、条件、业务数据、超时和错误恢复，
只在未知页面理解和语义定位处调用 `aiAct`、`aiQuery` 或 `aiAssert`。已知动作应直接调用
Phone MCP 或 Midscene 即时操作 API，避免把整段确定性业务逻辑反复交给模型规划。

Midscene 的 `aiAct` 会规划并立即执行，不把生成结果作为稳定 JavaScript API 返回。
其 planning cache 可以复用计划，但 Android 不具备 Web XPath 定位缓存，缓存命中也不能
保证完全停止 VLM 调用。因此，Phone MCP 集成不能把 Midscene cache 当作生产流程代码，
而应提供独立的流程记录和固化机制。

### 10.2 探索、回放与自适应模式

语义自动化支持三种明确模式：

- `explore`：使用 `aiAct` 和 VLM 探索未知流程，并记录每个真实动作前后的上下文。
- `replay`：只运行审核通过的确定性 TypeScript/JSON DSL，不允许调用 VLM。
- `adaptive`：优先确定性回放；前置条件或选择器失效时才调用 VLM 恢复，并输出新的候选
  流程版本，不能静默覆盖已审核流程。

生产环境默认使用 `replay`；流程维护可使用 `adaptive`；新流程录制使用 `explore`。
缓存只作为探索和自适应模式的性能优化，不改变这三个模式的安全语义。

### 10.3 从语义动作生成确定性步骤

Phone MCP 的 Midscene 适配器通过 `beforeInvokeAction` 和 `afterInvokeAction` 钩子记录
动作、参数、执行前后的 `phone_capture_context` 和验证结果。记录器根据 Midscene 的
定位坐标，在节点树中选择包含该点的最小可操作节点，并按以下优先级生成稳定选择器：

1. `resource_id`。
2. `content_description`。
3. `text` 与 `class_name` 组合。
4. 父节点语义与子节点关系。
5. OCR 文字和限定区域。
6. 归一化坐标，仅作为最后的显式兜底。

`snapshot_id` 和 `node_id` 只在临时快照中有效，禁止写入持久流程。固化步骤保存稳定
选择器，并在每次回放时重新调用 `phone_ui_snapshot` / `phone_ui_find` 获取当前节点 ID。
每个页面跳转或有副作用的动作应包含前置条件和结果验证，例如前台包名、节点存在性、
屏幕方向、关键文字或页面指纹。验证失败立即停止当前确定性流程，不得继续在未知页面
执行坐标动作。

推荐的流程产物包含：格式版本、目标应用与版本范围、生成时间、来源 prompt、动作步骤、
前置条件、后置条件、风险等级、所需权限和内容哈希。自动生成的 TypeScript/JSON DSL
必须经过测试或人工确认后才能进入 `replay` 模式。

### 10.4 租约、安全与隐私

语义流程开始时获取控制租约，长流程按期续租，结束或失败时释放。Midscene 的一次
`aiAct` 可能包含多个真实动作，但不能因此获得整段流程的无限授权；Phone MCP 仍对每个
底层动作执行权限、风险确认和审计。付款、授权、删除、发送消息、安装和敏感输入不能由
录制器自动降级为无确认回放。

VLM 模式会把截图发送给用户配置的模型提供商。适配器应支持敏感页面禁止上传、截图
区域遮罩、模型提供商 allowlist 和本地模型策略；`replay` 模式不得因为生成报告而隐式
上传截图。

## 11. 后续阶段

### 第二阶段

- 交互式 Headscale 认证 URL 和节点重新认证流程。
- Tailnet连接质量、DERP和网络切换的增强诊断。
- 应用安装卸载和权限管理。
- 上传下载、媒体查询和分享。
- 系统设置、网络控制和屏幕比较。
- 受限 `phone_run_script`。
- stdio 到远端 Streamable HTTP 的桌面桥接器。
- 基于 Phone MCP `AbstractInterface` 的 Midscene 适配器和流程记录器。

### 第三阶段

- Shizuku、Device Owner 和可选 Root 能力适配。
- 受控 `phone_shell_exec`。
- 联系人、短信、电话、日历和位置可选模块。
- 面向典型任务的高层工作流工具，但继续保留底层原子工具。
- 经过审核的语义流程 TypeScript/JSON DSL 编译、版本管理和自适应修复。

## 12. 测试与验收

至少覆盖：

- Android 版本、屏幕尺寸、旋转方向和深浅色主题组合。
- 原生 View、Compose、WebView、Canvas 和无障碍节点缺失页面。
- 前台应用变化导致的过期快照与动作拒绝。
- 多客户端争抢控制租约和断线自动释放。
- tsnet AAR 在 arm64-v8a 和 armeabi-v7a 上的构建和启动。
- Headscale 首次注册、一次性 key 清除、持久状态恢复、登出和重新注册。
- Headscale 直连、DERP 中继、网络切换、锁屏、Doze 和客户端掉线。
- Go HTTP 入口的鉴权、Origin、超大请求、超时、连接关闭和 Handler 分发。
- WhoIs 身份伪造、内部头注入、签名重放和非 Tailnet 端口访问。
- Tailscale 依赖升级后的协议、Headscale兼容、APK体积、内存和电量回归。
- 截图/OCR超时、权限被撤销、前台服务被系统回收。
- 安装、删除、Shell、敏感输入和用户拒绝确认。
- 路径穿越、命令注入、超大请求、重放、暴力配对和越权调用。
- MCP Inspector 的 schema、structuredContent、annotations 和错误结果验证。
- Midscene `explore` 生成稳定选择器、`replay` 全程零 VLM 调用和 `adaptive` 失败回退。
- 页面文案、布局、应用版本和屏幕方向变化时，流程前置条件应阻止错误点击。
- 语义流程的租约续期、逐动作确认、审计、敏感截图遮罩和失败释放。

建立一组跨应用端到端任务作为回归用例，例如：打开设置并读取系统版本、在浏览器中
搜索、通过通知回复、填写表单、使用 OCR 点击自绘按钮、上传文件并通过分享面板选择
目标应用。每个任务应验证最终状态，而不是只验证工具调用成功。

## 13. 参考资料

- [Tailscale tsnet](https://tailscale.com/docs/features/tsnet)
- [tsnet.Server API](https://tailscale.com/docs/reference/tsnet-server-api)
- [Tailscale Android Client 源码](https://github.com/tailscale/tailscale-android)
- [Tailscale 自定义控制服务器](https://tailscale.com/docs/how-to/set-up-custom-control-server)
- [Headscale Android Client 接入](https://headscale.net/stable/usage/connect/android/)
- [MCP Streamable HTTP Transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [Midscene Android](https://midscenejs.com/zh/platforms/android)
- [Midscene 自定义界面集成](https://midscenejs.com/zh/integrate-with-any-interface)
- [Midscene JavaScript 与 YAML 工作流](https://midscenejs.com/zh/automate-with-scripts-in-yaml)
- [Midscene AI 规划和定位缓存](https://midscenejs.com/zh/caching)
- [Midscene 数据隐私](https://midscenejs.com/zh/data-privacy)
