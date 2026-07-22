from contextlib import asynccontextmanager
from uuid import uuid4

import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from . import __version__
from .providers import ProviderRegistry, get_provider_registry
from .schemas import (
    EmbeddingRequest,
    EmbeddingResponse,
    GenerateRequest,
    GenerateResponse,
    RerankRequest,
    RerankResponse,
    RerankResult,
)
from .settings import Settings, get_settings


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.settings = get_settings()
    app.state.providers = get_provider_registry()
    yield


app = FastAPI(
    title="AI Hotspot AI API",
    version=__version__,
    lifespan=lifespan,
)


@app.middleware("http")
async def correlation_id_middleware(request: Request, call_next):
    correlation_id = request.headers.get("x-correlation-id") or str(uuid4())
    request.state.correlation_id = correlation_id
    response = await call_next(request)
    response.headers["x-correlation-id"] = correlation_id
    return response


@app.exception_handler(RuntimeError)
async def runtime_error_handler(request: Request, exception: RuntimeError):
    return JSONResponse(
        status_code=503,
        content={
            "code": "PROVIDER_UNAVAILABLE",
            "detail": str(exception),
            "correlationId": request.state.correlation_id,
        },
    )


def _settings(request: Request) -> Settings:
    return request.app.state.settings


def _providers(request: Request) -> ProviderRegistry:
    return request.app.state.providers


@app.get("/health")
async def health(request: Request):
    settings = _settings(request)
    return {
        "status": "UP",
        "service": "ai-api",
        "version": settings.version,
        "environment": settings.environment,
        "correlationId": request.state.correlation_id,
    }


@app.get("/api/v1/providers")
async def providers(request: Request):
    registry = _providers(request)
    return {
        "generation": {"provider": registry.generation.name, "model": registry.generation.model},
        "embedding": {"provider": registry.embedding.name, "model": registry.embedding.model},
        "rerank": {"provider": registry.rerank.name, "model": registry.rerank.model},
    }


@app.post("/api/v1/generate", response_model=GenerateResponse)
@app.post("/api/v1/mock/generate", response_model=GenerateResponse, include_in_schema=False)
async def generate(payload: GenerateRequest, request: Request):
    provider = _providers(request).generation
    output = await provider.generate_with_usage(payload.prompt, payload.max_tokens)
    return GenerateResponse(
        text=output.text,
        provider=provider.name,
        model=provider.model,
        input_tokens=output.input_tokens,
        output_tokens=output.output_tokens,
    )


@app.post("/api/v1/embed", response_model=EmbeddingResponse)
@app.post("/api/v1/mock/embed", response_model=EmbeddingResponse, include_in_schema=False)
async def embed(payload: EmbeddingRequest, request: Request):
    provider = _providers(request).embedding
    return EmbeddingResponse(
        vectors=await provider.embed(payload.texts),
        provider=provider.name,
        model=provider.model,
    )


@app.post("/api/v1/rerank", response_model=RerankResponse)
@app.post("/api/v1/mock/rerank", response_model=RerankResponse, include_in_schema=False)
async def rerank(payload: RerankRequest, request: Request):
    provider = _providers(request).rerank
    documents = [(document.id, document.text) for document in payload.documents]
    results = await provider.rerank(payload.query, documents, payload.top_n)
    return RerankResponse(
        results=[RerankResult(id=result.id, score=result.score) for result in results],
        provider=provider.name,
        model=provider.model,
    )


def run() -> None:
    uvicorn.run("ai_hotspot_ai.main:app", host="0.0.0.0", port=8000, reload=False)
