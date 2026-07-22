with cases(case_key, query_text, filters_json, expected_json, tags) as (values
  ('openai-product', 'OpenAI ChatGPT 产品更新', '{}', '{"mustContainAny":["OpenAI","ChatGPT"]}', array['topic','company','product']),
  ('anthropic-model', 'Anthropic Claude 模型进展', '{}', '{"mustContainAny":["Anthropic","Claude"]}', array['topic','company','model']),
  ('google-gemini', 'Google Gemini 模型进展', '{}', '{"mustContainAny":["Google","Gemini","DeepMind"]}', array['topic','company','model']),
  ('deepseek-model', 'DeepSeek 模型进展', '{}', '{"mustContainAny":["DeepSeek"]}', array['topic','company','model']),
  ('qwen-model', '通义千问 Qwen 模型进展', '{}', '{"mustContainAny":["通义千问","Qwen"]}', array['topic','company','model']),
  ('huggingface-open', 'Hugging Face 开源模型生态', '{}', '{"mustContainAny":["Hugging Face","Transformers"]}', array['topic','open-source']),
  ('agent-engineering', 'AI Agent 智能体工程', '{}', '{"mustContainAny":["Agent","智能体"]}', array['topic','technology']),
  ('multimodal', '多模态模型技术', '{}', '{"mustContainAny":["多模态","multimodal"]}', array['topic','technology']),
  ('rag-retrieval', 'RAG 检索增强生成技术', '{}', '{"mustContainAny":["RAG","检索增强"]}', array['topic','technology']),
  ('ai-safety', 'AI 安全与模型对齐', '{}', '{"mustContainAny":["安全","对齐","safety","alignment"]}', array['topic','governance']),
  ('nvidia-accelerated-ai', 'NVIDIA AI 加速与 GPU', '{}', '{"mustContainAny":["NVIDIA","GPU","CUDA"]}', array['topic','infrastructure']),
  ('pytorch-framework', 'PyTorch 深度学习框架', '{}', '{"mustContainAny":["PyTorch"]}', array['topic','framework']),
  ('tensorflow-framework', 'TensorFlow 机器学习框架', '{}', '{"mustContainAny":["TensorFlow"]}', array['topic','framework']),
  ('vllm-serving', 'vLLM 大模型推理服务', '{}', '{"mustContainAny":["vLLM"]}', array['topic','inference']),
  ('llama-cpp-runtime', 'llama.cpp 本地模型推理', '{}', '{"mustContainAny":["llama.cpp","llama"]}', array['topic','inference','open-source']),
  ('langgraph-workflow', 'LangGraph 智能体工作流', '{}', '{"mustContainAny":["LangGraph"]}', array['topic','agent']),
  ('github-copilot', 'GitHub Copilot 编程助手', '{}', '{"mustContainAny":["GitHub","Copilot"]}', array['topic','coding']),
  ('mistral-model', 'Mistral AI 模型进展', '{}', '{"mustContainAny":["Mistral"]}', array['topic','company','model']),
  ('aws-machine-learning', 'AWS 机器学习与生成式 AI', '{}', '{"mustContainAny":["AWS","Amazon"]}', array['topic','cloud']),
  ('paddlepaddle-nlp', '百度飞桨 PaddlePaddle 与 PaddleNLP', '{}', '{"mustContainAny":["飞桨","PaddlePaddle","PaddleNLP"]}', array['topic','framework']),

  ('official-openai', 'OpenAI 官方发布了什么', '{"officialOnly":true}', '{"mustContainAny":["OpenAI","ChatGPT"],"officialOnly":true}', array['official','company']),
  ('official-anthropic', 'Anthropic Claude 官方更新', '{"officialOnly":true}', '{"mustContainAny":["Anthropic","Claude"],"officialOnly":true}', array['official','company']),
  ('official-google-ai', 'Google AI 官方模型更新', '{"officialOnly":true}', '{"mustContainAny":["Google","Gemini","DeepMind"],"officialOnly":true}', array['official','company']),
  ('official-qwen', '通义千问 Qwen 官方更新', '{"officialOnly":true}', '{"mustContainAny":["通义千问","Qwen"],"officialOnly":true}', array['official','company']),
  ('official-nvidia', 'NVIDIA 官方 AI 技术更新', '{"officialOnly":true}', '{"mustContainAny":["NVIDIA","GPU","CUDA"],"officialOnly":true}', array['official','infrastructure']),
  ('official-pytorch', 'PyTorch 官方框架更新', '{"officialOnly":true}', '{"mustContainAny":["PyTorch"],"officialOnly":true}', array['official','framework']),
  ('official-tensorflow', 'TensorFlow 官方框架更新', '{"officialOnly":true}', '{"mustContainAny":["TensorFlow"],"officialOnly":true}', array['official','framework']),
  ('official-vercel-ai', 'Vercel 官方 AI 开发更新', '{"officialOnly":true}', '{"mustContainAny":["Vercel","AI SDK"],"officialOnly":true}', array['official','developer-tools']),

  ('recent-7d-ai-model', '最近 7 天 AI 模型有哪些更新', '{"timeRange":"7d"}', '{"mustContainAny":["AI","模型","model"],"maxAgeDays":7}', array['time-filter','7d']),
  ('recent-7d-agent', '最近 7 天智能体 Agent 有哪些进展', '{"timeRange":"7d"}', '{"mustContainAny":["Agent","智能体"],"maxAgeDays":7}', array['time-filter','7d','agent']),
  ('recent-7d-multimodal', '最近 7 天多模态模型进展', '{"timeRange":"7d"}', '{"mustContainAny":["多模态","multimodal"],"maxAgeDays":7}', array['time-filter','7d','multimodal']),
  ('recent-30d-open-source', '最近 30 天开源 AI 项目更新', '{"timeRange":"30d"}', '{"mustContainAny":["开源","open source","GitHub"],"maxAgeDays":30}', array['time-filter','30d','open-source']),
  ('recent-30d-ai-safety', '最近 30 天 AI 安全研究', '{"timeRange":"30d"}', '{"mustContainAny":["安全","safety","security"],"maxAgeDays":30}', array['time-filter','30d','governance']),
  ('recent-90d-inference', '最近 90 天大模型推理优化', '{"timeRange":"90d"}', '{"mustContainAny":["推理","inference","vLLM","llama"] ,"maxAgeDays":90}', array['time-filter','90d','inference']),
  ('recent-90d-code-agent', '最近 90 天编程智能体进展', '{"timeRange":"90d"}', '{"mustContainAny":["编程","coding","code","Agent","智能体"],"maxAgeDays":90}', array['time-filter','90d','coding']),

  ('bilingual-llm', '大语言模型 LLM 的新研究', '{}', '{"mustContainAny":["大语言模型","LLM","large language model"]}', array['bilingual','model']),
  ('bilingual-agent', '智能体 Agent 的部署实践', '{}', '{"mustContainAny":["智能体","Agent","agentic"]}', array['bilingual','agent']),
  ('bilingual-multimodal', '多模态 multimodal 模型研究', '{}', '{"mustContainAny":["多模态","multimodal","vision-language"]}', array['bilingual','multimodal']),
  ('bilingual-retrieval', '检索增强生成 Retrieval-Augmented Generation', '{}', '{"mustContainAny":["检索增强","RAG","retrieval"]}', array['bilingual','rag']),
  ('bilingual-alignment', '模型对齐 alignment 与安全', '{}', '{"mustContainAny":["对齐","alignment","安全","safety"]}', array['bilingual','governance']),

  ('multi-source-agent', 'AI 智能体 Agent 最新发展有哪些', '{}', '{"mustContainAny":["Agent","智能体"],"minimumDistinctSources":2}', array['multi-source','agent']),
  ('multi-source-model-release', '近期大模型发布有哪些共同趋势', '{}', '{"mustContainAny":["模型","model","LLM"],"minimumDistinctSources":2}', array['multi-source','model']),
  ('multi-source-ai-safety', '不同信源如何讨论 AI 安全', '{}', '{"mustContainAny":["安全","safety","security"],"minimumDistinctSources":2}', array['multi-source','governance']),
  ('multi-source-code-ai', '编程助手和代码智能体有哪些进展', '{}', '{"mustContainAny":["编程","代码","coding","code","Copilot"],"minimumDistinctSources":2}', array['multi-source','coding']),
  ('multi-source-inference', '大模型推理和部署优化有哪些进展', '{}', '{"mustContainAny":["推理","inference","部署","serving"],"minimumDistinctSources":2}', array['multi-source','inference']),

  ('no-evidence-private-corpus', '仅查询未接入的私有企业知识库', '{"sourceType":"PRIVATE_CORPUS"}', '{"expectNoEvidence":true}', array['no-evidence','scope-filter']),
  ('no-evidence-patent-corpus', '仅查询未接入的专利数据库', '{"sourceType":"PATENT_DATABASE"}', '{"expectNoEvidence":true}', array['no-evidence','scope-filter']),
  ('no-evidence-medical-corpus', '仅查询未接入的临床病历库', '{"sourceType":"CLINICAL_RECORD"}', '{"expectNoEvidence":true}', array['no-evidence','scope-filter']),

  ('acl-public-openai', 'OpenAI 与 ChatGPT 的公开信息', '{}', '{"mustContainAny":["OpenAI","ChatGPT"],"publicOnly":true}', array['acl','public']),
  ('acl-public-agent', 'AI Agent 智能体公开资料', '{}', '{"mustContainAny":["Agent","智能体"],"publicOnly":true}', array['acl','public'])
)
insert into knowledge.evaluation_case(id, suite_id, case_key, input_data, expected_data, tags)
select gen_random_uuid(), suite.id, cases.case_key,
       jsonb_build_object('query', cases.query_text, 'topK', 20, 'filters', cases.filters_json::jsonb),
       cases.expected_json::jsonb,
       cases.tags
from knowledge.evaluation_suite suite
cross join cases
where suite.code='RAG_BASELINE_ZH'
on conflict(suite_id, case_key) do update
set input_data=excluded.input_data, expected_data=excluded.expected_data, tags=excluded.tags;

update knowledge.evaluation_suite
set version='3.0',
    thresholds='{"recallAt20":0.85,"ndcgAt10":0.80,"citationSupport":0.90,"aclLeaks":0,"minimumCases":50}'::jsonb
where code='RAG_BASELINE_ZH';
