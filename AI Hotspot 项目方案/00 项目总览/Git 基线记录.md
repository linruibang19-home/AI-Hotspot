# AI Hotspot Git 基线记录

> 基线日期：2026-07-17
> 基线标签：`planning-v1`
> 后续里程碑标签：`prototype-v1`、`m1-foundation-v1`、`m2-identity-sources-v1`、`m3-rss-vertical-slice-v1`

## 1. 仓库

- 根目录：`D:\Dev\Autumn recruitment project\AI Hotspot`；
- 默认分支：`main`；
- 远程仓库：无；
- Git 作者：使用用户已配置的全局 Git 身份；
- 未创建或推送任何远程仓库。

## 2. 基线包含

- 原始 `AI_PULSE_DEVELOPMENT_SPEC.md`；
- 原始 `AI_PULSE_PROTOTYPE.html`；
- `AI Hotspot 项目方案` 中文方案目录；
- 七张 AI HOT 界面参考截图；
- 截图说明；
- `.gitignore`；
- `.gitattributes`。

## 3. 基线检查

- 截图复制前后 SHA-256 一致；
- 未发现 `.env`、私钥或明显真实 API Key；
- 数据卷、日志、模型缓存、抓取原件、构建产物和真实密钥已加入忽略规则；
- Markdown 方案结构检查通过；
- 当前基线不包含业务工程代码；
- 当前基线不包含远程推送。

## 4. 查询方式

```powershell
git status
git log --oneline --decorate -5
git show --stat planning-v1
git remote -v
```

标签只在基线提交完成后创建。后续不得移动或覆盖 `planning-v1`；若方案重新冻结，创建新的递增标签。

## 5. 后续分支

下一阶段方案详细设计应从 `main` 创建 `docs/<主题>` 分支；原型修改应创建 `prototype/<页面或阶段>` 分支。未经产品负责人审核，不把结构性原型修改直接提交到 `main`。

## 6. 里程碑 Git 记录

- `planning-v1`：原始材料与中文方案基线；
- `prototype-v1`：正式原型及 M0 浏览器验收；
- `feat/m1-foundation`：M1 工程任务分支；
- `b308020`：M1 工程、基础设施、测试与运行脚本实现提交；
- `m1-foundation-v1`：M1 方案记录与验收完成后的本地里程碑标签；
- `m2-identity-sources-v1`：M2 身份权限与信源管理本地里程碑标签；
- `feat/m3-rss-vertical-slice`：M3 实现与验收任务分支；
- `m3-rss-vertical-slice-v1`：M3 合并 `main` 后创建的本地里程碑标签；
- 未配置 Git Remote，未 push、未创建远程 PR、未部署生产。
