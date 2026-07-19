update knowledge.provider_config
set model_name = 'deepseek-v4-flash',
    base_url = 'https://api.deepseek.com',
    updated_at = now()
where task_type in ('AGENT', 'CONTENT_ANALYSIS', 'RAG_GENERATION')
  and lower(provider_name) = 'deepseek'
  and model_name in ('deepseek-chat', 'deepseek-reasoner');

update knowledge.provider_config
set base_url = 'https://api.siliconflow.cn/v1',
    updated_at = now()
where task_type in ('EMBEDDING', 'RERANK')
  and lower(provider_name) = 'baai'
  and (base_url is null or btrim(base_url) = '');
