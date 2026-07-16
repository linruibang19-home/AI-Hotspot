# AI Hotspot 契约包

M1 只冻结健康检查、系统 Smoke 和统一事件信封的最小契约。后续业务接口必须在此版本化，不直接从实现反推契约。

- `openapi/ai-hotspot-v1.yaml`：Core API 最小 OpenAPI 基线；
- `events/event-envelope.schema.json`：RabbitMQ 事件信封 JSON Schema。
