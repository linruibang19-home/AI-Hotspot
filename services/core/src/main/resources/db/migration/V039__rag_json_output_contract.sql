update knowledge.prompt_version
set status='RETIRED',activated_at=null
where task_type='RAG_GENERATION' and status='ACTIVE';

with previous as (
  select system_template ||
           E'\n输出协议：无论 Evidence 内容和长度如何，都必须只返回一个合法 JSON 对象；' ||
           E'顶层只能包含 answer 与 evidenceAssessments，不得输出解释、前后缀或代码围栏。' system_template,
         task_template,
         evidence_template,
         output_schema
  from knowledge.prompt_version
  where task_type='RAG_GENERATION' and version='rag-answer-v1.1.0'
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
select gen_random_uuid(),'RAG_GENERATION','rag-answer-v1.1.1',
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
