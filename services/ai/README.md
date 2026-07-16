# AI Hotspot AI API / Worker

开发环境默认启用确定性的 Mock Provider，因此没有 DeepSeek、Embedding 或 Rerank Key 也能启动和测试。

```powershell
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -e '.[dev]'
./.venv/Scripts/python.exe -m uvicorn ai_hotspot_ai.main:app --reload
```

运行 Worker：

```powershell
./.venv/Scripts/ai-hotspot-worker.exe
```

M1 暂不启用真实远程 Provider；配置为非 Mock 时会快速失败，防止误以为真实模型已经接通。
