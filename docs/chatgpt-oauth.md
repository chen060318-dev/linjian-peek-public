# ChatGPT OAuth 安全部署

本分支把手机同步认证与 ChatGPT 用户认证分成两层：

- `LINJIAN_TOKEN`：仅供 Android App、Python 手机后端与 MCP 服务之间同步。由 Render 自动生成，不写入仓库，不作为 ChatGPT 登录凭据。
- OAuth 2.1 Bearer Token：仅供 ChatGPT 调用 MCP。MCP 服务会验证 JWT 签名、issuer、audience、有效期和 scope。

默认只注册三个工具：

- `get_phone_state`：电量、充电、网络、亮屏、当前 App 和权限状态
- `get_app_usage`：今日屏幕时长、解锁次数和应用时长排行
- `get_profile`：当前 OAuth 连接身份

截图、屏幕文字、点击、滑动、输入、打开 App、通知、钱包和外卖工具仍保留在代码中，但默认不会注册，也不能通过 `/mcp-wallet` 调用。

## 推荐身份服务：Auth0

不要自己实现 OAuth 授权服务器。使用 Auth0 或另一家满足 OAuth 2.1、PKCE S256、发现文档与 JWKS 的身份服务。

### 1. 先完成 Render Blueprint 创建

在 Render 的 Blueprint 确认页：

1. 找到 `MCP_OAUTH_ISSUER`
2. 填入 Auth0 tenant issuer，例如 `https://YOUR-TENANT.us.auth0.com/`
3. 保持 `MCP_ENABLE_SENSITIVE_TOOLS=false`
4. 保持 `MCP_ENABLE_WALLET_TOOLS=false`
5. 创建两个服务，记下 MCP 服务最终地址，例如 `https://zhangxinchuang-mcp-xxxx.onrender.com`

在 OAuth 完整配置以前，不要把 MCP 插件分享给其他人。即使服务已经启动，缺少或错误的 OAuth 配置也会 fail closed，所有 MCP 入口返回 401 或 503。

### 2. 在 Auth0 创建 API

在 Auth0 Dashboard 打开 **Applications → APIs → Create API**：

- Name: `掌心窗 ChatGPT MCP`
- Identifier / Audience: MCP 服务 origin，例如 `https://zhangxinchuang-mcp-xxxx.onrender.com`
- Signing Algorithm: `RS256`

在 Permissions 中新增：

- `phone.read`：读取手机状态与应用时长
- `phone.control`：未来才使用的控制权限；当前不要授予

建议在 API Settings 中启用 RBAC，并把权限加入 Access Token。只给自己的 Auth0 用户分配所需权限。在数据库连接设置中开启 **Disable Sign Ups**，避免陌生人注册后获得访问资格。

Auth0 开启 RBAC 与 **Add Permissions in the Access Token** 后，会把直接分配的 API 权限放入已签名 JWT 的 `permissions` 数组；OAuth 客户端实际请求的范围仍可能同时出现在 `scope`。MCP 会在完成签名、issuer 与 audience 校验后，合并 `scope`、`scp` 和 `permissions` 作为有效授权集合。

如果 Auth0 API Identifier 与 Render 自动生成的 MCP 地址不同，请在 Render 的 MCP 服务 Environment 中新增：

- `MCP_OAUTH_AUDIENCE` = Auth0 API Identifier

可选的额外锁定：

- `MCP_ALLOWED_SUBJECTS` = 你的 Auth0 `sub`；多个值用英文逗号分隔

### 3. 在 Auth0 创建 ChatGPT OAuth Client

在 **Applications → Applications → Create Application** 创建 Web Application。创建后保留 Client ID 和 Client Secret，只在 ChatGPT 的插件连接页面填写，不要写入 GitHub，也不要放进 Android App。

在 ChatGPT 创建自定义 MCP 插件时，页面会显示 OAuth callback URL。把这个完整 URL复制到 Auth0 Application 的 **Allowed Callback URLs**，再保存。

ChatGPT 连接设置：

- Server URL: `https://你的-mcp-服务.onrender.com/mcp`
- Authentication: `OAuth`
- Client ID / Client Secret: Auth0 Application 的值
- Scope: `phone.read`

ChatGPT 会通过 `/.well-known/oauth-protected-resource` 发现 issuer，并使用授权码 + PKCE 完成登录。MCP 服务会在每次请求上重新验证 Token。

## 安全入口

以下入口全部需要有效 OAuth Bearer Token：

- `POST /mcp`
- `GET /mcp`
- `GET /sse`
- `POST /messages`
- `POST /mcp-wallet`
- `GET /mcp-wallet`

`/messages` 还会把 SSE session 绑定到 OAuth `sub`，防止另一个已登录用户劫持 session。

公开入口仅有：

- `GET /`
- `GET /health`（只返回布尔配置状态，不泄露后端 URL 或 Token）
- `GET /.well-known/oauth-protected-resource`

Python 手机后端的全部 `/api/*` 路由统一要求 `X-Auth-Token`。Token 不再接受 query string，避免出现在浏览器历史、代理日志和访问日志中。现有 Android App 已使用 `X-Auth-Token`，无需改 APK。

## 环境变量

| 变量 | 用途 | 是否敏感 |
| --- | --- | --- |
| `LINJIAN_TOKEN` | Android 与手机后端/MCP 内部同步 | 是，Render 自动生成 |
| `MCP_OAUTH_ISSUER` | OAuth issuer，必须与 Token `iss` 完全一致 | 否 |
| `MCP_OAUTH_AUDIENCE` | OAuth API Identifier；默认使用 Render MCP origin | 否 |
| `MCP_OAUTH_JWKS_URI` | 可选，自定义 JWKS 地址 | 否 |
| `MCP_OAUTH_READ_SCOPE` | 默认 `phone.read` | 否 |
| `MCP_OAUTH_CONTROL_SCOPE` | 默认 `phone.control` | 否 |
| `MCP_ALLOWED_SUBJECTS` | 可选 OAuth `sub` 白名单 | 建议私密保存 |
| `MCP_ENABLE_SENSITIVE_TOOLS` | 是否注册控制工具，默认 `false` | 否 |
| `MCP_ENABLE_WALLET_TOOLS` | 是否开放钱包入口，默认 `false` | 否 |

不要在未完成单独安全评审、权限分级和确认流程前把两个敏感开关改为 `true`。

## 测试

`mcp/test/security.test.mjs` 会启动临时 JWKS/OAuth 测试环境并验证：

- 无凭据访问所有 MCP 入口均返回 401
- 无效 Bearer Token 被拒绝
- 有效、正确 audience 与 scope 的 Token 可以初始化 MCP
- 有效 control scope 仍无法绕过默认关闭的 wallet 入口
- 公开 protected-resource metadata 正确声明 issuer 与 scope

Render Blueprint 的 MCP buildCommand 会执行 `npm ci && npm test`。测试失败时部署不会继续。

