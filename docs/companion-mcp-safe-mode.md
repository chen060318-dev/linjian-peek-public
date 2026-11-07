# 掌心窗：日常陪伴工具模式（安全默认关闭）

这个模式专门用于 ChatGPT 中的日记、行动记录、窗语、小金库申请及审批、守护日历和轻量生活记录。
它不会开放手机截图、读取屏幕节点、点击/输入、应用门禁、删除账本和日记、外卖自动操作。

## 开启方式

1. 将包含此功能的分支合并到部署仓库，并在 Render 重新部署 `zhangxinchuang-mcp`。
2. 在 MCP 服务的 Render Environment 里新增/修改 `MCP_ENABLE_COMPANION_TOOLS=true`，保留 `MCP_ENABLE_SENSITIVE_TOOLS=false` 与 `MCP_ENABLE_WALLET_TOOLS=false`。
3. 掌心窗现有 MCP URL `https://<你的-mcp-域名>/mcp` 不必改变。OAuth access token 需要 **同时** 具备 `phone.read` 和 `phone.control` scope 才能在普通 `/mcp` 获取日常陪伴工具；只有 `phone.read` 的 token 仍只会看到原来的三项只读工具。
4. 在 ChatGPT 连接器中刷新/重新连接掌心窗，让工具列表重新被发现。测试 `list_wallet_approvals`、`get_companion_actions`、`list_diary_books`，并检查不能调用 `tap_text`、`peek_screen`。
5. 即使工具注册成功，实际写入仍依赖手机端在线、执行器同步，并可能等待手机确认；审批仅更新掌心窗记录，不会代替用户付款。

> 安全注意：`phone.control` 是 OAuth 权限名称，并不意味着此模式会向聊天提供完整屏幕控制工具。实际能力由服务器端的 `COMPANION_ALLOWED_TOOLS` 严格白名单限制。不要同时打开 `MCP_ENABLE_SENSITIVE_TOOLS=true`，否则会覆盖限制，暴露完整功能。最好将 OAuth access 限制在自己的账户，并设置 `MCP_ALLOWED_SUBJECTS`。

## 当前支持的主要功能

- `get_companion_actions`、`get_activity_events`：读取 Riven 的行动记录。
- `write_diary_entry`、`list_diary_books`、`read_diary_entry`、批注工具：写、读、更新日记（无删除入口）。
- `list_wallet_approvals`、`decide_wallet_approval`、`save_wallet_request_result`：查看并处理用户的花钱申请；不会直接扣款。
- `get_guardian_calendar`、`add_guardian_day`：纪念日管理。
- `get_window_whisper`、`set_window_whisper`、到访记录、天气与轻量通知：陪伴内容。

**主动通知的边界：** MCP 工具不会自动让 ChatGPT 在后台收到 App 事件。若需要「提交申请后 Riven 主动来审批」，要另外设置定时检查或可信事件触发流程。

## 开关

- `MCP_ENABLE_SENSITIVE_TOOLS=false`：默认禁用全部敏感远程控制。
- `MCP_ENABLE_COMPANION_TOOLS=true`：启用经过筛选的日常陪伴工具（仍要求 OAuth `phone.control`）。
- `MCP_ENABLE_WALLET_TOOLS=false`：单独的 `/mcp-wallet` 路由仍然关闭；普通 `/mcp` 内经白名单允许的审批工具依然可用。
