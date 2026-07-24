# AI Hotspot 契约包

这里维护 Core API 和 RabbitMQ 事件信封的版本化契约。Controller 新增、删除或改变 HTTP 方法/路径时，必须在同一变更中同步 OpenAPI。

- `openapi/ai-hotspot-v1.yaml`：Core API 现行 OpenAPI 契约；
- `events/event-envelope.schema.json`：RabbitMQ 事件信封 JSON Schema。
- `scripts/contracts/verify_openapi.py`：比较 Spring Controller 与 OpenAPI 的方法/路径，发现缺失或陈旧操作时失败。

本地执行：

```text
pip install -r scripts/contracts/requirements.txt
python scripts/contracts/verify_openapi.py
```

CI 的 `contracts` Job 会执行同一检查。检查会拒绝无效 YAML、重复键、缺失路由和陈旧路由；Schema 和业务语义仍需在评审中维护。
