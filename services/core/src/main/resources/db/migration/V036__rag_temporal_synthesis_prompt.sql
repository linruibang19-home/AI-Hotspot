update knowledge.prompt_version
set status='RETIRED',activated_at=null
where task_type='RAG_GENERATION' and status='ACTIVE';

with previous as (
  select system_template,
         replace(
           task_template,
           '5. 不把单一媒体观点写成共识；answer 使用纯文本，不使用 Markdown。',
           '5. 不把单一媒体观点写成共识；answer 使用纯文本，不使用 Markdown。' || E'\n' ||
           '6. 对近期进展、趋势和变化类问题，按技术方向归纳同类证据，写明证据日期，优先采用较新且权威的来源。' || E'\n' ||
           '7. 不把同一事件的重复报道计作多个独立进展；证据无法支持时间或因果关系时明确说明。'
         ) task_template,
         evidence_template,
         output_schema
  from knowledge.prompt_version
  where task_type='RAG_GENERATION' and version='rag-answer-v1.0.2'
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
select gen_random_uuid(),'RAG_GENERATION','rag-answer-v1.1.0',
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

update knowledge.evaluation_suite
set version='4.1'
where code='RAG_BASELINE_ZH';

insert into knowledge.evaluation_case(
  id,suite_id,case_key,input_data,expected_data,tags,dataset_split
)
select gen_random_uuid(),id,'recent-inference-auto-window',
       '{"query":"近期大模型推理优化有哪些进展？","topK":20,"filters":{}}',
       '{"mustContainAny":["推理","inference","serving","部署"],"maxAgeDays":30,"minimumDistinctSources":2}',
       array['time-filter','auto-window','multi-source','inference'],'CANARY'
from knowledge.evaluation_suite where code='RAG_BASELINE_ZH'
on conflict(suite_id,case_key) do update
set input_data=excluded.input_data,expected_data=excluded.expected_data,
    tags=excluded.tags,dataset_split=excluded.dataset_split;
