import httpx

from .base import GenerationProvider


class OpenAICompatibleGenerationProvider(GenerationProvider):
    name = "openai-compatible"

    def __init__(self, base_url: str, api_key: str, model: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model

    async def generate(self, prompt: str, max_tokens: int | None = None) -> str:
        payload: dict[str, object] = {
            "model": self.model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.1,
        }
        if "json" in prompt.lower() or "recommendationReason" in prompt:
            payload["response_format"] = {"type": "json_object"}
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens
        async with httpx.AsyncClient(timeout=45) as client:
            response = await client.post(
                f"{self.base_url}/chat/completions",
                headers={"Authorization": f"Bearer {self.api_key}"},
                json=payload,
            )
            response.raise_for_status()
            data = response.json()
            return str(data["choices"][0]["message"]["content"])
