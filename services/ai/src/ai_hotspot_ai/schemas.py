from pydantic import BaseModel, Field, model_validator


class ProviderOverride(BaseModel):
    provider_name: str = Field(min_length=1, max_length=40)
    api_protocol: str = Field(default="OPENAI_COMPATIBLE", max_length=40)
    base_url: str = Field(min_length=8, max_length=500)
    api_key: str = Field(min_length=1, max_length=500)
    model: str = Field(min_length=1, max_length=200)
    timeout_ms: int = Field(default=40_000, ge=1_000, le=180_000)
    retry_attempts: int = Field(default=1, ge=0, le=1)


class GenerateRequest(BaseModel):
    prompt: str | None = Field(default=None, min_length=1, max_length=20_000)
    system_prompt: str | None = Field(default=None, min_length=1, max_length=20_000)
    user_prompt: str | None = Field(default=None, min_length=1, max_length=20_000)
    evidence: str | None = Field(default=None, min_length=1, max_length=40_000)
    max_tokens: int | None = Field(default=None, ge=64, le=2_000)
    provider_override: ProviderOverride | None = None

    @model_validator(mode="after")
    def require_prompt(self):
        if not self.prompt and not self.user_prompt:
            raise ValueError("prompt or user_prompt is required")
        return self


class GenerateResponse(BaseModel):
    text: str
    provider: str
    model: str
    input_tokens: int | None = None
    output_tokens: int | None = None


class EmbeddingRequest(BaseModel):
    texts: list[str] = Field(min_length=1, max_length=32)
    provider_override: ProviderOverride | None = None


class EmbeddingResponse(BaseModel):
    vectors: list[list[float]]
    provider: str
    model: str


class RerankDocument(BaseModel):
    id: str
    text: str


class RerankRequest(BaseModel):
    query: str = Field(min_length=1, max_length=2_000)
    documents: list[RerankDocument] = Field(min_length=1, max_length=100)
    top_n: int = Field(default=8, ge=1, le=100)
    provider_override: ProviderOverride | None = None


class RerankResult(BaseModel):
    id: str
    score: float


class RerankResponse(BaseModel):
    results: list[RerankResult]
    provider: str
    model: str
