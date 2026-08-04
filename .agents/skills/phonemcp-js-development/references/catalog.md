# PhoneMCP JavaScript resource catalog

Use paths relative to the repository root. The canonical JavaScript examples live under `app/src/main/assets-app/sample/`; the API pages live under `app/src/main/assets-app/docs/`.

## Capability map

| Capability | Example directories | Primary API pages |
| --- | --- | --- |
| Accessibility and semantic UI automation | `无障碍服务/`, `事件与监听/` | `automator.html`, `uiSelectorType.html`, `uiObjectType.html`, `uiObjectCollectionType.html`, `uiObjectActionsType.html`, `keys.html` |
| App UI, layouts, and widgets | `布局/`, `控件/`, `画布/` | `ui.html`, `activity.html`, `context.html`, `canvas.html`, `androidRectType.html` |
| Dialogs, console, toast, and notifications | `对话框/`, `控制台/` | `dialogs.html`, `console.html`, `consoleBuildOptionsType.html`, `toast.html`, `notice.html`, `noticeBuilderType.html`, `noticeOptionsType.html` |
| Floating windows | `浮动窗口/` | `floaty.html` |
| Apps, intents, and Android integration | `应用/`, `Java API/` | `app.html`, `appType.html`, `intentType.html`, `scriptingJava.html`, `apiLevel.html` |
| Device, vibration, display, and audio | `设备/` | `device.html`, `media.html` |
| Touch, gestures, and global actions | `无障碍服务/` | `automator.html`, `keys.html` |
| Screenshots, images, colors, and OpenCV | `图像与颜色/`, `画布/` | `image.html`, `imageWrapperType.html`, `color.html`, `colorType.html`, `opencvPointType.html`, `opencvRectType.html`, `opencvSizeType.html` |
| OCR, barcode, and QR code | `OCR/`, `图像与颜色/` | `ocr.html`, `ocrOptionsType.html`, `barcode.html`, `qrcode.html` |
| Files and local storage | `文件读写/`, `本地存储/` | `files.html`, `storages.html`, `storageType.html` |
| HTTP, WebSocket, and web integration | `HTTP/` | `http.html`, `httpRequestBuilderOptionsType.html`, `httpResponseType.html`, `httpResponseBodyType.html`, `webSocketType.html`, `web.html` |
| Shell, root, and Shizuku | `Shell/` | `shell.html`, `shizuku.html` |
| Engines, modules, and runtime | `脚本引擎/`, `AutoJs6/`, `JavaScript/` | `engines.html`, `modules.html`, `runtime.html`, `global.html`, `autojs.html`, `dataTypes.html` |
| Threads and continuations | `多线程/`, `协程/` | `threads.html`, `continuation.html` |
| Events, timers, and scheduled tasks | `事件与监听/`, `定时器/`, `任务/` | `events.html`, `eventEmitterType.html`, `timers.html`, `tasks.html` |
| Sensors and recorder | `传感器/`, `记录器/` | `sensors.html`, `recorder.html` |
| Encoding, cryptography, and utilities | `工具/` | `base64.html`, `crypto.html`, `cryptoCipherOptionsType.html`, `cryptoKeyType.html`, `util.html`, `s13n.html`, `i18n.html`, `opencc.html` |
| Plugins | `AutoJs6/` and feature-specific examples | `plugins.html` |
| Runtime verification | `测试/` | `qa.html`, `exceptions.html`, plus the page for the API under test |

## Complete example categories

The sample tree is organized into these top-level categories:

`AutoJs6`, `HTTP`, `Java API`, `JavaScript`, `OCR`, `Shell`, `事件与监听`, `任务`, `传感器`, `协程`, `图像与颜色`, `多线程`, `定时器`, `对话框`, `工具`, `布局`, `应用`, `控件`, `控制台`, `文件读写`, `无障碍服务`, `本地存储`, `测试`, `浮动窗口`, `画布`, `脚本引擎`, `记录器`, `设备`.

Always inspect all files in the selected category before deciding that an API has no example. Some demonstrations are multi-file directories. Prefer an explicitly versioned modern example over a file marked `[Legacy]`.

## Documentation families

- Start with `overview.html`, `manual.html`, and `global.html` for runtime-wide behavior.
- Feature pages such as `automator.html`, `device.html`, and `http.html` describe top-level globals and modules.
- Pages ending in `Type.html` document option objects, returned objects, enums, and methods linked from feature pages.
- Glossary pages define protocol constants and shared values.
- `changelog.html` and versioned sample filenames help resolve compatibility questions.
- Files in `docs/` at the repository root describe PhoneMCP architecture, MCP tools, design decisions, and development workflows; they do not replace the runtime API pages.

The HTML pages contain navigation markup before their main content. Search for the exact method heading or identifier, then read the surrounding definition, parameters, return value, exceptions, and example—not just the first textual match.

## Search recipes

```bash
# Search filenames and content across examples and API docs.
.agents/skills/phonemcp-js-development/scripts/search_resources.sh "requestScreenCapture" all

# Find semantic selector usage only in runnable examples.
.agents/skills/phonemcp-js-development/scripts/search_resources.sh "findOne" examples

# Locate project-level MCP design decisions.
.agents/skills/phonemcp-js-development/scripts/search_resources.sh "phone_call_js_api" design
```
