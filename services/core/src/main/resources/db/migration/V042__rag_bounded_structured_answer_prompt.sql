update knowledge.prompt_version
set status='RETIRED',activated_at=null
where task_type='RAG_GENERATION' and status='ACTIVE';

with previous as (
  select system_template,
         task_template ||
           E'\n11. answer 正文控制在 600 个中文字符以内，每个要点不超过 2 句。' ||
           E'\n12. evidenceAssessments 只列 answer 实际引用的编号；claimText 与 reason 各不超过 40 个中文字符，优先保证 JSON 完整闭合。'
           as task_template,
         evidence_template,
         output_schema
  from knowledge.prompt_version
  where task_type='RAG_GENERATION' and version='rag-answer-v1.2.0'
), hashed as (
  select *,
    encode(digest(system_template||E'\n'||task_template||E'\n'||evidence_template,'sha256'),'hex')
      template_hash
  from previous
)
insert into knowledge.prompt_version(
  id,task_type,version,template,status,output_schema,
  system_template,task_template,evidence_template,template_hash,activated_at
)
select gen_random_uuid(),'RAG_GENERATION','rag-answer-v1.2.1',
       system_template||E'\n\n'||task_template||E'\n\n'||evidence_template,
       'ACTIVE',output_schema,system_template,task_template,evidence_template,template_hash,now()
from hashed
on conflict(task_type,version) do update
set template=excluded.template,
    status='ACTIVE',
    output_schema=excluded.output_schema,
    system_template=excluded.system_template,
    task_template=excluded.task_template,
    evidence_template=excluded.evidence_template,
    template_hash=excluded.template_hash,
    activated_at=now();
