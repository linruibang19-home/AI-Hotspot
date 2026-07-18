alter table content.content_item
    add column search_tsv tsvector generated always as (
        to_tsvector('simple',
            coalesce(title_zh, '') || ' ' ||
            coalesce(original_title, '') || ' ' ||
            coalesce(summary_zh, '') || ' ' ||
            coalesce(category_code, ''))
    ) stored;

create index ix_content_item_public_search
    on content.content_item using gin (search_tsv)
    where publication_status = 'PUBLISHED'
      and visibility = 'PUBLIC'
      and admission_status = 'PASSED'
      and is_duplicate = false
      and fact_status <> 'DEBUNKED';

create table content.topic (
    id uuid primary key,
    slug text not null,
    group_code text not null,
    name text not null,
    description text not null,
    query_text text not null,
    status text not null default 'ACTIVE',
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_topic_slug unique (slug),
    constraint ck_topic_group check (group_code in ('COMPANY_MODEL', 'TECHNOLOGY', 'CONTENT_FORM')),
    constraint ck_topic_status check (status in ('ACTIVE', 'HIDDEN', 'ARCHIVED')),
    constraint ck_topic_sort_order check (sort_order >= 0)
);

create index ix_topic_group_order on content.topic (group_code, sort_order, id) where status = 'ACTIVE';

create table content.content_topic (
    content_item_id uuid not null,
    topic_id uuid not null,
    assignment_source text not null default 'RULE',
    confidence numeric(5,2) not null default 100,
    created_at timestamptz not null default now(),
    primary key (content_item_id, topic_id),
    constraint fk_content_topic__content foreign key (content_item_id) references content.content_item (id) on delete cascade,
    constraint fk_content_topic__topic foreign key (topic_id) references content.topic (id) on delete cascade,
    constraint ck_content_topic_source check (assignment_source in ('AI', 'RULE', 'EDITOR')),
    constraint ck_content_topic_confidence check (confidence between 0 and 100)
);

create index ix_content_topic_topic on content.content_topic (topic_id, content_item_id);

create table content.event_topic (
    event_cluster_id uuid not null,
    topic_id uuid not null,
    assignment_source text not null default 'RULE',
    confidence numeric(5,2) not null default 100,
    created_at timestamptz not null default now(),
    primary key (event_cluster_id, topic_id),
    constraint fk_event_topic__event foreign key (event_cluster_id) references content.event_cluster (id) on delete cascade,
    constraint fk_event_topic__topic foreign key (topic_id) references content.topic (id) on delete cascade,
    constraint ck_event_topic_source check (assignment_source in ('AI', 'RULE', 'EDITOR')),
    constraint ck_event_topic_confidence check (confidence between 0 and 100)
);

create index ix_event_topic_topic on content.event_topic (topic_id, event_cluster_id);

create table content.report_issue (
    id uuid primary key,
    period text not null,
    start_date date not null,
    end_date date not null,
    volume text not null,
    headline text not null,
    lead text not null,
    status text not null default 'DRAFT',
    story_count integer not null default 0,
    event_count integer not null default 0,
    source_count integer not null default 0,
    official_source_count integer not null default 0,
    featured_count integer not null default 0,
    estimated_minutes integer not null default 0,
    generation_metadata jsonb not null default '{}'::jsonb,
    version bigint not null default 0,
    generated_at timestamptz not null default now(),
    published_at timestamptz,
    published_by uuid,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_report_issue_period_start unique (period, start_date),
    constraint fk_report_issue__publisher foreign key (published_by) references iam.user_account (id),
    constraint ck_report_issue_period check (period in ('DAILY', 'WEEKLY', 'MONTHLY')),
    constraint ck_report_issue_status check (status in ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    constraint ck_report_issue_dates check (end_date >= start_date),
    constraint ck_report_issue_counts check (
        story_count >= 0 and event_count >= 0 and source_count >= 0 and
        official_source_count >= 0 and featured_count >= 0 and estimated_minutes >= 0)
);

create index ix_report_issue_public_archive on content.report_issue (period, start_date desc, id desc)
    where status = 'PUBLISHED';
create index ix_report_issue_editor_queue on content.report_issue (status, updated_at desc, id desc);

create table content.report_section (
    id uuid primary key,
    report_issue_id uuid not null,
    section_code text not null,
    title text not null,
    summary text,
    sort_order integer not null,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_report_section__issue foreign key (report_issue_id) references content.report_issue (id) on delete cascade,
    constraint uq_report_section_code unique (report_issue_id, section_code),
    constraint uq_report_section_order unique (report_issue_id, sort_order),
    constraint ck_report_section_order check (sort_order >= 0)
);

create table content.report_item (
    id uuid primary key,
    report_section_id uuid not null,
    content_item_id uuid not null,
    position integer not null,
    editor_note text,
    created_at timestamptz not null default now(),
    constraint fk_report_item__section foreign key (report_section_id) references content.report_section (id) on delete cascade,
    constraint fk_report_item__content foreign key (content_item_id) references content.content_item (id),
    constraint uq_report_item_content unique (report_section_id, content_item_id),
    constraint uq_report_item_position unique (report_section_id, position),
    constraint ck_report_item_position check (position >= 0)
);

create index ix_report_item_content on content.report_item (content_item_id, report_section_id);

create table content.favorite (
    user_id uuid not null,
    target_type text not null,
    target_id uuid not null,
    created_at timestamptz not null default now(),
    primary key (user_id, target_type, target_id),
    constraint fk_favorite__user foreign key (user_id) references iam.user_account (id) on delete cascade,
    constraint ck_favorite_target_type check (target_type in ('CONTENT', 'EVENT'))
);

create index ix_favorite_user_recent on content.favorite (user_id, created_at desc, target_type, target_id);

insert into content.topic (id, slug, group_code, name, description, query_text, sort_order) values
    (gen_random_uuid(), 'openai-chatgpt', 'COMPANY_MODEL', 'OpenAI / ChatGPT', 'GPT、ChatGPT、Codex 与 OpenAI 研究和产品动态。', 'OpenAI ChatGPT GPT Codex', 10),
    (gen_random_uuid(), 'anthropic-claude', 'COMPANY_MODEL', 'Anthropic / Claude', 'Claude 模型、Claude Code、安全研究与产品进展。', 'Anthropic Claude', 20),
    (gen_random_uuid(), 'google-gemini', 'COMPANY_MODEL', 'Google / Gemini', 'Google、DeepMind、Gemini 与 AI 基础设施动态。', 'Google Gemini DeepMind', 30),
    (gen_random_uuid(), 'microsoft-copilot', 'COMPANY_MODEL', 'Microsoft / Copilot', 'Copilot、Azure AI 与微软研究和企业产品更新。', 'Microsoft Copilot Azure', 40),
    (gen_random_uuid(), 'meta-llama', 'COMPANY_MODEL', 'Meta / Llama', 'Llama 模型、Meta AI 研究与开源生态。', 'Meta Llama', 50),
    (gen_random_uuid(), 'nvidia', 'COMPANY_MODEL', 'NVIDIA 英伟达', 'GPU、CUDA、推理平台与 AI 工厂动态。', 'NVIDIA 英伟达 CUDA', 60),
    (gen_random_uuid(), 'hugging-face', 'COMPANY_MODEL', 'Hugging Face', '模型、数据集、Transformers 与开源社区进展。', 'Hugging Face Transformers', 70),
    (gen_random_uuid(), 'deepseek', 'COMPANY_MODEL', 'DeepSeek', 'DeepSeek 模型、开源权重、API 与技术报告。', 'DeepSeek', 80),
    (gen_random_uuid(), 'qwen', 'COMPANY_MODEL', '通义千问 Qwen', 'Qwen 模型、Agent、ModelScope 与阿里 AI 生态。', 'Qwen 通义 千问 阿里', 90),
    (gen_random_uuid(), 'hunyuan', 'COMPANY_MODEL', '腾讯混元', '混元模型、Agent 和腾讯 AI 产品动态。', '腾讯 混元 Hunyuan', 100),
    (gen_random_uuid(), 'baidu-paddle', 'COMPANY_MODEL', '百度文心 / Paddle', '文心、飞桨、百度研究与开发者生态。', '百度 文心 飞桨 Paddle', 110),
    (gen_random_uuid(), 'bytedance-seed', 'COMPANY_MODEL', '字节跳动 Seed', 'Seed 模型、火山引擎与字节 AI 研究。', '字节 Seed 火山引擎 豆包', 120),
    (gen_random_uuid(), 'kimi', 'COMPANY_MODEL', 'Kimi / 月之暗面', 'Kimi 模型、长上下文与智能体产品进展。', 'Kimi 月之暗面 Moonshot', 130),
    (gen_random_uuid(), 'glm', 'COMPANY_MODEL', '智谱 GLM', 'GLM 模型、智谱开放平台与产业动态。', '智谱 GLM', 140),
    (gen_random_uuid(), 'minimax', 'COMPANY_MODEL', 'MiniMax', '多模态模型、语音视频和 Agent 产品进展。', 'MiniMax', 150),
    (gen_random_uuid(), 'agent', 'TECHNOLOGY', 'Agent 智能体', '自主规划、工具调用、记忆与多步任务。', 'Agent 智能体 agentic', 10),
    (gen_random_uuid(), 'ai-coding', 'TECHNOLOGY', 'AI 编码', 'Coding Agent、IDE、代码模型与工程工作流。', 'coding code 编程 Codex', 20),
    (gen_random_uuid(), 'reasoning', 'TECHNOLOGY', '推理能力', '推理模型、数学与逻辑能力。', 'reasoning 推理', 30),
    (gen_random_uuid(), 'multimodal', 'TECHNOLOGY', '多模态', '视觉、文本、音视频统一理解与生成。', 'multimodal 多模态', 40),
    (gen_random_uuid(), 'image-generation', 'TECHNOLOGY', '图像生成', '文生图、图像编辑与视觉创作工具。', 'image 图像 视觉', 50),
    (gen_random_uuid(), 'ai-video', 'TECHNOLOGY', 'AI 视频', '视频生成、理解、编辑与影视创作。', 'video 视频', 60),
    (gen_random_uuid(), 'audio', 'TECHNOLOGY', '语言与音频', '语音识别、合成、实时对话与音乐生成。', 'audio speech voice 音频 语音', 70),
    (gen_random_uuid(), 'embodied-ai', 'TECHNOLOGY', '具身智能', '机器人、世界模型与现实环境操作。', 'robot 机器人 具身', 80),
    (gen_random_uuid(), 'on-device-ai', 'TECHNOLOGY', '端侧 AI', '手机、PC 与边缘设备的小模型和芯片。', 'on-device edge 端侧 边缘', 90),
    (gen_random_uuid(), 'open-source', 'TECHNOLOGY', '开源生态', '开源模型、框架、权重与社区项目。', 'open source 开源 GitHub', 100),
    (gen_random_uuid(), 'deployment', 'TECHNOLOGY', '部署工程', '推理优化、显存成本、Serving 与算力。', 'inference deployment serving 推理 部署', 110),
    (gen_random_uuid(), 'training', 'TECHNOLOGY', '数据与训练', '数据集、预训练、后训练与算力成本。', 'training dataset 训练 数据集', 120),
    (gen_random_uuid(), 'safety', 'TECHNOLOGY', '安全对齐', '模型安全、越狱防御、评测与治理。', 'safety alignment 安全 对齐', 130),
    (gen_random_uuid(), 'mcp-tools', 'TECHNOLOGY', 'MCP 与工具调用', 'MCP、Function Calling 与外部工具集成。', 'MCP tool calling 工具调用', 140),
    (gen_random_uuid(), 'model-release', 'CONTENT_FORM', '模型发布', '新模型、开源权重、性能与价格变化。', '模型 发布 release model', 10),
    (gen_random_uuid(), 'product-update', 'CONTENT_FORM', '产品更新', 'AI 产品功能、改版、商业化与生态进展。', '产品 更新 launch update', 20),
    (gen_random_uuid(), 'research-paper', 'CONTENT_FORM', '论文研究', 'AI 论文、研究成果、方法与理论进展。', '论文 研究 paper research arxiv', 30),
    (gen_random_uuid(), 'benchmark', 'CONTENT_FORM', '评测基准', 'Benchmark、评测争议与排行榜变化。', 'benchmark 评测 基准', 40),
    (gen_random_uuid(), 'tutorial', 'CONTENT_FORM', '教程实践', '提示词、工作流、工具用法与实战经验。', 'tutorial 教程 实践', 50),
    (gen_random_uuid(), 'opinion', 'CONTENT_FORM', '大佬观点', '创始人、研究者与投资人的判断。', '观点 访谈 interview opinion', 60),
    (gen_random_uuid(), 'trend', 'CONTENT_FORM', '现象与趋势', '能力涌现、用户迁移与市场格局观察。', '趋势 trend', 70),
    (gen_random_uuid(), 'industry', 'CONTENT_FORM', '行业动态', '融资并购、合作竞争与商业信号。', '行业 融资 并购 market', 80),
    (gen_random_uuid(), 'policy', 'CONTENT_FORM', '政策监管', 'AI 立法、出口管制与全球治理。', '政策 监管 regulation policy', 90);

insert into iam.permission (id, code, description, created_at)
values
    (gen_random_uuid(), 'report:read', '读取报告编辑数据', now()),
    (gen_random_uuid(), 'report:manage', '生成、编辑和发布日报周报月报', now())
on conflict (code) do nothing;

insert into iam.role_permission (role_id, permission_id)
select r.id, p.id
from iam.role r
join iam.permission p on
    (r.code in ('EDITOR', 'OPERATOR', 'ADMIN') and p.code = 'report:read')
    or (r.code in ('OPERATOR', 'ADMIN') and p.code = 'report:manage')
on conflict do nothing;
