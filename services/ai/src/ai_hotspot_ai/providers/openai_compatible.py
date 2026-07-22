import httpx

from .base import GenerationOutput, GenerationProvider


class OpenAICompatibleGenerationProvider(GenerationProvider):
    name = "openai-compatible"

    def __init__(self, base_url: str, api_key: str, model: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model

    async def generate(self, prompt: str, max_tokens: int | None = None) -> str:
        return (await self.generate_with_usage(prompt, max_tokens)).text

    async def generate_with_usage(
        self, prompt: str, max_tokens: int | None = None
    ) -> GenerationOutput:
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
            usage = data.get("usage") or {}
            return GenerationOutput(
                text=str(data["choices"][0]["message"]["content"]),
                input_tokens=_token_count(usage, "prompt_tokens", "input_tokens"),
                output_tokens=_token_count(usage, "completion_tokens", "output_tokens"),
            )


def _token_count(usage: dict[str, object], *names: str) -> int | None:
    for name in names:
        value = usage.get(name)
        if isinstance(value, int) and value >= 0:
            return value
    return None
