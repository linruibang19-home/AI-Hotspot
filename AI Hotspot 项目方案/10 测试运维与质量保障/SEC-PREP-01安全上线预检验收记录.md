# SEC-PREP-01 安全上线预检验收记录

> 验收时间：2026-07-25
>
> 范围：SEC-01 可在本地完成的生产配置门禁、SMTP 安全参数和 SSRF 回归

## 1. 验收结论

SEC-PREP-01 通过，SEC-01 尚未整体完成。Core、AI API 和 Worker 现在会在 `AI_HOTSPOT_ENV=production` 时拒绝弱默认凭据、开发开关、Mock Provider、不安全公开 URL 或不完整的生产 SMTP 配置；development 模式保持现有 Docker Compose 行为。

生产密钥实际轮换、真实 SMTP 投递、目标域名 HTTPS 和生产账号仍需要目标环境与新凭据。本轮没有读取、打印、提交或替换 `.env` 中的密钥，也没有用测试字符串冒充已轮换凭据。

## 2. 交付内容

| 能力 | 结果 |
|---|---|
| Core 启动门禁 | 生产模式要求 Secure Cookie、关闭烟测与引导管理员、HTTPS 公开地址、独立强基础设施凭据及 SMTP 认证/TLS |
| AI 启动门禁 | 生产模式禁止三类 Mock Provider，要求 HTTPS Provider 地址、非空 API Key，并拒绝本地默认数据库、Redis、RabbitMQ 和 MinIO 凭据 |
| 错误保密 | 门禁错误只报告变量名和规则；Pydantic 隐藏输入值，不把凭据带入 ValidationError |
| SMTP 能力 | Core 新增用户名、密码、AUTH、STARTTLS 和 STARTTLS REQUIRED 配置，开发 Mailpit 默认仍为匿名明文内网连接 |
| 上线预检 | `scripts/Test-Production-Security.ps1` 可检查 `.env`，内置强/弱双向自测且从不输出配置值 |
| SSRF 回归 | Core 和 Worker 覆盖环回、RFC1918、链路本地云元数据、IPv6 环回及 ULA 地址；Worker 每次重定向均重新校验 |

## 3. 本地真实检查

当前开发 `.env` 的生产预检按预期返回 FAIL，共识别 21 项生产缺口，包括：

- 环境仍为 development，开发烟测和引导管理员仍开启；
- Session Cookie 尚未要求 Secure，公开地址尚未切换 HTTPS；
- PostgreSQL、Redis、RabbitMQ、MinIO 等本地凭据需要分别轮换且不能复用；
- SMTP 仍指向 Mailpit，未配置认证、STARTTLS、生产发件域名和账号；
- Email Code Pepper 与退订签名密钥需要轮换。

真实 Generation、Embedding 和 Rerank Provider 的模式、HTTPS 地址及 API Key 长度检查已通过。这里只表示配置形态符合门禁，不表示密钥从未曝光或已经完成生产轮换。

## 4. 自动化证据

| 门禁 | 结果 |
|---|---|
| 安全预检强配置自测 | PASS |
| 安全预检弱配置自测 | PASS，能拒绝默认密码和 Mock Provider |
| Core 生产弱配置镜像 | 启动前 fail closed，退出码 1，错误不回显默认密码 |
| AI 生产弱配置镜像 | 启动前 fail closed，退出码 3，Pydantic 输入已隐藏 |
| Core 测试 | 48/48 PASS |
| AI Ruff 与测试 | 52/52 PASS |
| Compose 配置解析 | PASS |
| Development 回归 | Core、AI API、Worker 重建后健康；真实三类 Provider 可用，Beta Readiness PASS，错误日志 0 |

## 5. 剩余外部验收

1. 用户或目标环境负责人提供并轮换所有曾展示的 Provider Key、数据库、中间件、SMTP、Cookie/验证码和退订密钥。
2. 生产部署使用 `AI_HOTSPOT_ENV=production`，通过预检后再启动服务。
3. 使用真实域名和邮箱验证 SMTP AUTH、STARTTLS、SPF、DKIM、DMARC、验证码、日报/周报、退订和失败重试。
4. 在 Linux/HTTPS 目标机复核只有 80/443 暴露公网，并执行权限、SSRF、日志脱敏与镜像扫描。
5. 生产网络增加出站访问控制或固定代理，并验证 DNS 重绑定场景；应用层已在请求和每次重定向前解析并拒绝非公网地址，但不能替代基础设施级 egress 限制。
