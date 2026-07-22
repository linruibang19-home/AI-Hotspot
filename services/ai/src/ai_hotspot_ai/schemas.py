from pydantic import BaseModel, Field


class GenerateRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=20_000)
    max_tokens: int | None = Field(default=None, ge=64, le=2_000)


class GenerateResponse(BaseModel):
    text: str
    provider: str
    model: str


class EmbeddingRequest(BaseModel):
    texts: list[str] = Field(min_length=1, max_length=32)


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


class RerankResult(BaseModel):
    id: str
    score: float


class RerankResponse(BaseModel):
    results: list[RerankResult]
    provider: str
    model: str
