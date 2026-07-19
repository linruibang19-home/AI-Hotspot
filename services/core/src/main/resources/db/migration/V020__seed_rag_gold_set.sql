insert into knowledge.evaluation_case(id, suite_id, case_key, input_data, expected_data, tags)
select gen_random_uuid(), s.id, v.case_key,
       jsonb_build_object('query', v.query_text, 'topK', 20),
       jsonb_build_object('mustContainAny', v.expected_terms),
       v.tags
from knowledge.evaluation_suite s
cross join (values
  ('openai-product', 'OpenAI ChatGPT', array['OpenAI','ChatGPT'], array['company','product']),
  ('anthropic-model', 'Anthropic Claude', array['Anthropic','Claude'], array['company','model']),
  ('google-gemini', 'Google Gemini', array['Google','Gemini','DeepMind'], array['company','model']),
  ('deepseek-model', 'DeepSeek 模型', array['DeepSeek'], array['company','model']),
  ('qwen-model', '通义千问 Qwen', array['通义千问','Qwen'], array['company','model']),
  ('huggingface-open', 'Hugging Face 开源模型', array['Hugging Face','开源'], array['open-source']),
  ('agent-engineering', 'AI Agent 智能体', array['Agent','智能体'], array['technology']),
  ('multimodal', '多模态模型', array['多模态','multimodal'], array['technology']),
  ('rag-retrieval', 'RAG 检索增强生成', array['RAG','检索增强'], array['technology']),
  ('ai-safety', 'AI 安全对齐', array['安全','对齐','safety','alignment'], array['governance'])
) as v(case_key, query_text, expected_terms, tags)
where s.code='RAG_BASELINE_ZH'
on conflict(suite_id, case_key) do update
set input_data=excluded.input_data, expected_data=excluded.expected_data, tags=excluded.tags;

update knowledge.evaluation_suite
set version='2.0',
    thresholds='{"recallAt20":0.80,"ndcgAt10":0.70,"citationSupport":0.95,"aclLeaks":0,"minimumCases":10}'::jsonb
where code='RAG_BASELINE_ZH';
