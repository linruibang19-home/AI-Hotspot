-- 历史采集失败在端点后续成功或端点归档后已失去重放价值，但仍需保留审计记录。
-- 将它们标记为 IGNORED，而不是删除；真正尚未恢复的失败仍保持 PENDING 并阻塞发布就绪。
update messaging.dead_letter_record d
set replay_status = 'IGNORED'
from source.source_endpoint e
where d.replay_status = 'PENDING'
  and e.id = (d.payload #>> '{payload,endpointId}')::uuid
  and (e.status = 'ARCHIVED' or e.last_success_at > d.last_failed_at);
