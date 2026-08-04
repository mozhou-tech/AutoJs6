---
name: phonemcp-js-development
description: Use when creating, editing, explaining, reviewing, or debugging PhoneMCP/AutoJs6 JavaScript automation scripts, or when validating PhoneMCP MCP tools with the standalone Pi Agent SDK test harness. Uses this repository's bundled examples, HTML API documentation, MCP schemas, JVM tests, in-memory integration tests, and Android emulator smoke tests. Covers accessibility and UI automation, device control, files, HTTP, OCR, images, dialogs, events, tasks, shell, floating windows, and related runtime APIs.
---

# PhoneMCP JavaScript Development

Build PhoneMCP scripts against the repository's actual runtime APIs. Treat the bundled JavaScript examples and generated HTML documentation as the source of truth instead of assuming Node.js or browser behavior.

## Locate the source material

Resolve the repository root first:

```bash
repo_root="$(git rev-parse --show-toplevel)"
```

Canonical resources are:

- `$repo_root/app/src/main/assets-app/sample/` for runnable `.js` examples.
- `$repo_root/app/src/main/assets-app/docs/` for API and type-reference `.html` files.
- `$repo_root/docs/` for architecture and project-level design documents.

Read [references/catalog.md](references/catalog.md) to map a requested capability to likely example directories and API pages. For an unfamiliar API, run:

```bash
.agents/skills/phonemcp-js-development/scripts/search_resources.sh "search term" all
```

Use `examples`, `api`, or `design` instead of `all` to narrow the search. Search both the English API identifier and its Chinese concept when useful.

## Develop a script

1. Identify the requested behavior, required permissions, minimum Android/PhoneMCP version, and whether the script needs UI mode.
2. Read at least one relevant API page and the closest runnable example before writing code. Follow linked type pages when return values or options are unclear.
3. Reuse the repository's established calling style. These scripts run in the PhoneMCP/AutoJs6 JavaScript engine, not Node.js or a web browser.
4. Put `'ui';` at the beginning only for scripts that construct an application UI. Do not add it to ordinary automation scripts.
5. Prefer accessibility selectors and semantic control actions over fixed coordinates. Use coordinates or image matching only when the target exposes no reliable semantic node.
6. Request or check capabilities before using screenshots, accessibility, root, Shizuku, notifications, floating windows, or other privileged features. Handle denial and timeout paths explicitly.
7. Treat suffixes such as `[v6.7.0+]` and `[Legacy]` in example filenames as compatibility constraints. Prefer the newest non-legacy variant compatible with the target.
8. Bound waits and loops, release images and other native resources, stop listeners and threads, and avoid logging secrets or clipboard contents.

Do not edit canonical examples or generated HTML documentation unless the user explicitly asks to update those sources. Write new user scripts in the requested destination and keep imports or companion files together when an example is multi-file.

## Review and validate

Before handing off a script:

- Verify every non-standard global, method, option, and return type against the bundled docs or an example.
- Check selector uniqueness, null results, timeouts, permission failures, screen-size dependence, and cleanup.
- Check UI-thread rules for UI scripts and concurrency rules for threads, events, timers, and continuations.
- Preserve the API's canonical identifiers even where the bundled docs still use the AutoJs6 product name; PhoneMCP branding does not rename runtime symbols.
- Report the exact example and documentation paths used so the implementation can be audited.

When device execution is available, perform a dry run with harmless inputs first. Never trigger purchases, destructive file operations, account changes, or message sending without explicit authorization.

## Test MCP tools

Read [references/mcp-testing.md](references/mcp-testing.md) before changing MCP schemas, executors, the JavaScript bridge, Pi Agent adaptation, or the tool catalog. Use its layered workflow:

1. Run Android-side JVM contract tests.
2. Run standalone Pi adapter and in-memory transport tests.
3. Build the independent TypeScript component.
4. Run live tool discovery and read-only smoke tests against an emulator when available.
5. Test control tools only with an acquired lease and harmless, reversible inputs.

Keep `phone-mcp-agent-tests/` independent of Android and Gradle. Do not add model credentials to basic tool tests, and never store the pairing token in files, command history examples, snapshots, or logs.

## Update this skill

The examples and HTML pages remain the only copies of the source material. When categories or APIs change, update [references/catalog.md](references/catalog.md), [references/mcp-testing.md](references/mcp-testing.md), and the search workflow, rather than copying source files into this skill.
