from dataclasses import dataclass
from functools import lru_cache

from ai_hotspot_ai.settings import Settings, get_settings

from .base import EmbeddingProvider, GenerationProvider, RerankProvider
from .mock import MockEmbeddingProvider, MockGenerationProvider, MockRerankProvider
from .openai_compatible import OpenAICompatibleGenerationProvider


@dataclass(frozen=True, slots=True)
class ProviderRegistry:
    generation: GenerationProvider
    embedding: EmbeddingProvider
    rerank: RerankProvider


def build_provider_registry(settings: Settings) -> ProviderRegistry:
    if settings.embedding_provider != "mock" or settings.rerank_provider != "mock":
        raise RuntimeError("Remote embedding/rerank adapters are not enabled until the RAG stage")
    if settings.generation_provider == "openai-compatible":
        if not settings.generation_base_url or not settings.generation_api_key:
            raise RuntimeError("GENERATION_BASE_URL and GENERATION_API_KEY are required")
        generation = OpenAICompatibleGenerationProvider(
            settings.generation_base_url, settings.generation_api_key, settings.generation_model
        )
    else:
        generation = MockGenerationProvider()
    return ProviderRegistry(
        generation=generation,
        embedding=MockEmbeddingProvider(),
        rerank=MockRerankProvider(),
    )


@lru_cache
def get_provider_registry() -> ProviderRegistry:
    return build_provider_registry(get_settings())
