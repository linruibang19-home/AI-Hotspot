update knowledge.evaluation_case c
set input_data=jsonb_set(c.input_data,'{query}',to_jsonb(v.query_text::text)),
    tags=v.tags
from knowledge.evaluation_suite s
join (values
  ('multi-source-agent', '结合多个来源分析 AI 智能体 Agent 的技术与产品进展', array['multi-source','multi-hop','agent']),
  ('multi-source-model-release', '官方发布与媒体报道对近期大模型趋势的说法是否一致', array['multi-source','conflict','model']),
  ('multi-source-ai-safety', '不同信源对 AI 安全风险的观点是否存在冲突', array['multi-source','conflict','governance']),
  ('multi-source-inference', '结合框架、项目和研究信源分析大模型推理部署进展', array['multi-source','multi-hop','inference']),
  ('acl-public-openai', '在公开权限范围内检索 OpenAI 与 ChatGPT 信息', array['acl','private-boundary','public']),
  ('acl-public-agent', '在公开权限范围内检索 AI Agent 智能体资料', array['acl','private-boundary','public'])
) as v(case_key,query_text,tags) on true
where s.id=c.suite_id and s.code='RAG_BASELINE_ZH' and c.case_key=v.case_key;
