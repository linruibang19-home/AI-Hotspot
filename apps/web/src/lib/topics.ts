export type TopicGroup = {
  title: string;
  description: string;
  topics: Array<{ name: string; query: string; description: string }>;
};

export const topicGroups: TopicGroup[] = [
  {
    title: "公司与模型",
    description: "按厂商与模型系追踪：谁发布了什么，又赢了哪一局",
    topics: [
      { name: "OpenAI / ChatGPT", query: "OpenAI", description: "GPT、ChatGPT、Codex 与 OpenAI 研究和产品动态。" },
      { name: "Anthropic / Claude", query: "Claude", description: "Claude 模型、Claude Code、安全研究与产品进展。" },
      { name: "Google / Gemini", query: "Gemini", description: "Google、DeepMind、Gemini 与 AI 基础设施动态。" },
      { name: "Microsoft / Copilot", query: "Microsoft", description: "Copilot、Azure AI、研究与企业产品更新。" },
      { name: "Meta / Llama", query: "Llama", description: "Llama 模型、Meta AI 研究与开源生态。" },
      { name: "NVIDIA 英伟达", query: "NVIDIA", description: "GPU、CUDA、推理平台与 AI 工厂动态。" },
      { name: "Hugging Face", query: "Hugging Face", description: "模型、数据集、Transformers 与开源社区进展。" },
      { name: "DeepSeek", query: "DeepSeek", description: "DeepSeek 模型、开源权重、API 与技术报告。" },
      { name: "通义千问 Qwen", query: "Qwen", description: "Qwen 模型、Agent、ModelScope 与阿里 AI 生态。" },
      { name: "腾讯混元", query: "混元", description: "腾讯混元模型、Agent 和产业应用动态。" },
      { name: "百度文心 / Paddle", query: "Paddle", description: "文心、飞桨、百度研究与开发者生态。" },
      { name: "字节跳动 Seed", query: "Seed", description: "Seed 模型、火山引擎与字节 AI 研究。" },
      { name: "Kimi / 月之暗面", query: "Kimi", description: "Kimi 模型、长上下文与智能体产品进展。" },
      { name: "智谱 GLM", query: "GLM", description: "GLM 模型、智谱开放平台与产业动态。" },
      { name: "MiniMax", query: "MiniMax", description: "多模态模型、语音视频和 Agent 产品进展。" },
    ],
  },
  {
    title: "技术方向",
    description: "按技术领域深挖：Agent、多模态、具身智能与基础设施",
    topics: [
      { name: "Agent 智能体", query: "Agent", description: "自主规划、工具调用、记忆与多步任务。" },
      { name: "AI 编码", query: "coding", description: "Coding Agent、IDE、代码模型与工程工作流。" },
      { name: "推理能力", query: "reasoning", description: "思维链、推理模型、数学与逻辑能力。" },
      { name: "多模态", query: "multimodal", description: "视觉、文本、音视频统一理解与生成。" },
      { name: "图像生成", query: "image", description: "文生图、图像编辑与视觉创作工具。" },
      { name: "AI 视频", query: "video", description: "视频生成、理解、编辑与影视创作。" },
      { name: "语言与音频", query: "audio", description: "语音识别、合成、实时对话与音乐生成。" },
      { name: "具身智能", query: "robot", description: "机器人、世界模型与现实环境操作。" },
      { name: "端侧 AI", query: "on-device", description: "手机、PC 与边缘设备的小模型和芯片。" },
      { name: "开源生态", query: "open source", description: "开源模型、框架、权重与社区项目。" },
      { name: "部署工程", query: "inference", description: "推理优化、显存成本、Serving 与算力。" },
      { name: "数据与训练", query: "training", description: "数据集、合成数据、预训练和后训练。" },
      { name: "安全对齐", query: "safety", description: "越狱防御、模型行为、安全评测与治理。" },
      { name: "MCP 与工具调用", query: "MCP", description: "MCP、function calling 与外部工具集成。" },
    ],
  },
  {
    title: "内容形态",
    description: "按内容类型浏览：发布、研究、实战、观点与产业信号",
    topics: [
      { name: "模型发布", query: "模型 发布", description: "新模型、开源权重、性能与价格变化。" },
      { name: "产品更新", query: "产品 更新", description: "AI 产品功能、改版、商业化与生态进展。" },
      { name: "论文研究", query: "研究", description: "AI 论文、研究成果、方法与理论进展。" },
      { name: "评测基准", query: "benchmark", description: "Benchmark、评测争议与排行榜变化。" },
      { name: "教程实践", query: "tutorial", description: "提示词、工作流、工具用法与实战经验。" },
      { name: "大佬观点", query: "观点", description: "创始人、研究者与投资人的判断。" },
      { name: "现象与趋势", query: "趋势", description: "用户迁移、能力涌现与市场格局观察。" },
      { name: "行业动态", query: "行业", description: "融资并购、合作竞争与商业信号。" },
      { name: "政策监管", query: "监管", description: "AI 立法、出口管制与全球治理。" },
    ],
  },
];
