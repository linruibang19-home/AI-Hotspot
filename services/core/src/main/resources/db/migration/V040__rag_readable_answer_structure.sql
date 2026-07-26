update knowledge.prompt_version
set status='RETIRED',activated_at=null
where task_type='RAG_GENERATION' and status='ACTIVE';

with previous as (
  select system_template,
         replace(
           task_template,
           'answer 使用纯文本，不使用 Markdown。',
           'answer 使用简洁、可读的 Markdown 层级，只允许二级/三级标题、段落、无序列表、加粗和编号引用。' || E'\n' ||
           '8. 开头先用 1～2 句给出直接结论；存在多个方向时，按主题使用“##”小标题和要点列表，不使用表格。' || E'\n' ||
           '9. 每个要点只表达一个可核验主张，并尽量写明日期、主体和对应引用；结论、重点变化与证据限制必须分层呈现。' || E'\n' ||
           '10. 不复制 Evidence 中的 Markdown 链接、Changelog 标记、提交 Hash 或大段英文原文，除非问题明确要求这些细节。'
         ) task_template,
         evidence_template,
         output_schema
  from knowledge.prompt_version
  where task_type='RAG_GENERATION' and version='rag-answer-v1.1.1'
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
select gen_random_uuid(),'RAG_GENERATION','rag-answer-v1.2.0',
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
