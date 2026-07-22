-- 修复 M10 保留任务历史上使用旧验证码表名而导致整批维护事务回滚的问题。
-- 迁移只处理已过期验证码，并把端点后来已经成功的采集死信标记为已忽略；
-- 审计记录本身不会删除。

delete from iam.email_verification_challenge
where created_at < now() - interval '7 days';

update messaging.dead_letter_record d
set replay_status = 'IGNORED'
from source.source_endpoint e
where d.replay_status = 'PENDING'
  and e.id = (d.payload #>> '{payload,endpointId}')::uuid
  and (e.status = 'ARCHIVED' or e.last_success_at > d.last_failed_at);
