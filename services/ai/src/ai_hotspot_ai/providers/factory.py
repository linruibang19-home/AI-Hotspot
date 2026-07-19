from dataclasses import dataclass
from functools import lru_cache

from ai_hotspot_ai.settings import Settings, get_settings

from .base import EmbeddingProvider, GenerationProvider, RerankProvider
from .mock import MockEmbeddingProvider, MockGenerationProvider, MockRerankProvider
from .openai_compatible import OpenAICompatibleGenerationProvider
from .remote import RemoteEmbeddingProvider, RemoteRerankProvider


@dataclass(frozen=True, slots=True)
class ProviderRegistry:
    generation: GenerationProvider
    embedding: EmbeddingProvider
    rerank: RerankProvider


def build_provider_registry(settings: Settings) -> ProviderRegistry:
    if settings.generation_provider == "openai-compatible":
        if not settings.generation_base_url or not settings.generation_api_key:
            raise RuntimeError("GENERATION_BASE_URL and GENERATION_API_KEY are required")
        generation = OpenAICompatibleGenerationProvider(
            settings.generation_base_url, settings.generation_api_key, settings.generation_model
        )
    else:
        generation = MockGenerationProvider()
    embedding = (RemoteEmbeddingProvider(settings.embedding_base_url, settings.embedding_api_key, settings.embedding_model)
                 if settings.embedding_provider == "remote" and settings.embedding_base_url else MockEmbeddingProvider())
    rerank = (RemoteRerankProvider(settings.rerank_base_url, settings.rerank_api_key, settings.rerank_model)
              if settings.rerank_provider == "remote" and settings.rerank_base_url else MockRerankProvider())
    return ProviderRegistry(generation=generation, embedding=embedding, rerank=rerank)


@lru_cache
def get_provider_registry() -> ProviderRegistry:
    return build_provider_registry(get_settings())
