from dataclasses import dataclass
from functools import lru_cache

from ai_hotspot_ai.settings import Settings, get_settings

from .base import EmbeddingProvider, GenerationProvider, RerankProvider
from .mock import MockEmbeddingProvider, MockGenerationProvider, MockRerankProvider


@dataclass(frozen=True, slots=True)
class ProviderRegistry:
    generation: GenerationProvider
    embedding: EmbeddingProvider
    rerank: RerankProvider


def build_provider_registry(settings: Settings) -> ProviderRegistry:
    unsupported = {
        "generation": settings.generation_provider,
        "embedding": settings.embedding_provider,
        "rerank": settings.rerank_provider,
    }
    non_mock = {name: provider for name, provider in unsupported.items() if provider != "mock"}
    if non_mock:
        configured = ", ".join(f"{name}={provider}" for name, provider in non_mock.items())
        raise RuntimeError(
            f"M1 only enables Mock Provider implementations; configured: {configured}"
        )
    return ProviderRegistry(
        generation=MockGenerationProvider(),
        embedding=MockEmbeddingProvider(),
        rerank=MockRerankProvider(),
    )


@lru_cache
def get_provider_registry() -> ProviderRegistry:
    return build_provider_registry(get_settings())
