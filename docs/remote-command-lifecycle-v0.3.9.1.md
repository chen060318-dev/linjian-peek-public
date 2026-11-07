# v0.3.9.1 远程命令生命周期与截图关联修复

本补丁修复 MCP 已入队但 Android 延迟执行、短时门禁收到时已经过期、截图完成却无法关联原请求，以及“queued”被误报成“已执行”的问题。

## 命令生命周期

每条命令都有唯一 `command_id`，并记录：

- `created_at` / `queued_at`
- `dispatched_at` / `dispatch_attempts`
- `phone_received_at`
- `execution_started_at`
- `execution_completed_at`
- `result_uploaded_at`
- `expires_at_ms` / `ttl_seconds`
- `status` / `result`

非终态包括 `pending`、`dispatched`、`received`、`execution_started`、`capturing`、`uploading`；终态包括 `completed`、`failed`、`expired`。

服务器使用 45 秒派发租约。手机没有确认收到时，同一个 `command_id` 最多重新派发三次；Android 保存最近 50 条最终回执，收到重复 ID 时只重放回执，不重复执行操作。

## 时长型门禁

`screen_break_app` 与兼容工具 `lock_app` 不再由 MCP 预先写死 `locked_until_ms`。Android 收到有效命令后，按真实执行时间加上 `duration_minutes` 计算结束时间。短时门禁命令默认 TTL 为 120 秒；超过 TTL 的旧命令会进入 `expired`，不会突然补执行。

## 主动截图

`peek_screen` 的截图上传携带 `X-Command-Id`。服务器保存的截图文件、命令状态与 MCP 返回图片都使用同一个 ID 关联。MCP 只返回本次请求对应的图片，不再用“最新文件修改时间”猜测，也不会用旧截图冒充新截图。

系统截图失败、图片为空、上传失败或命令过期都会返回明确终态。超时时会保留 `command_id` 和当前阶段，方便继续排查。

## 部署

- Render 的 `server` 与 `mcp` 服务需要部署本提交。
- 手机端必须安装 v0.3.9.1（30901）APK；只更新服务器无法获得 Android 去重、阶段回执及按执行时间计时。
- OAuth、包名和现有 UI 不需要重新配置。
