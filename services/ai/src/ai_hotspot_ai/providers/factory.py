from dataclasses import dataclass
from functools import lru_cache

from ai_hotspot_ai.settings import Settings, get_settings

from .base import EmbeddingProvider, GenerationProvider, RerankProvider
from .mock import MockEmbeddingProvider, MockGenerationProvider, MockRerankProvider
from .openai_compatible import OpenAICompatibleGenerationProvider
from .remote import RemoteEmbeddingProvider, RemoteRerankProvider
from .resilience import ProviderPolicy
from ai_hotspot_ai.schemas import ProviderOverride


@dataclass(frozen=True, slots=True)
class ProviderRegistry:
    generation: GenerationProvider
    embedding: EmbeddingProvider
    rerank: RerankProvider


def build_provider_registry(settings: Settings) -> ProviderRegistry:
    generation_policy = ProviderPolicy(
        settings.generation_connect_timeout_seconds,
        settings.generation_read_timeout_seconds,
        settings.generation_total_timeout_seconds,
        settings.provider_retry_attempts,
    )
    embedding_policy = ProviderPolicy(
        settings.embedding_connect_timeout_seconds,
        settings.embedding_read_timeout_seconds,
        settings.embedding_total_timeout_seconds,
        settings.provider_retry_attempts,
    )
    rerank_policy = ProviderPolicy(
        settings.rerank_connect_timeout_seconds,
        settings.rerank_read_timeout_seconds,
        settings.rerank_total_timeout_seconds,
        settings.provider_retry_attempts,
    )
    if settings.generation_provider == "openai-compatible":
        if not settings.generation_base_url or not settings.generation_api_key:
            raise RuntimeError("GENERATION_BASE_URL and GENERATION_API_KEY are required")
        generation = OpenAICompatibleGenerationProvider(
            settings.generation_base_url,
            settings.generation_api_key,
            settings.generation_model,
            generation_policy,
        )
    else:
        generation = MockGenerationProvider()
    embedding = (
        RemoteEmbeddingProvider(
            settings.embedding_base_url,
            settings.embedding_api_key,
            settings.embedding_model,
            embedding_policy,
        )
        if settings.embedding_provider == "remote" and settings.embedding_base_url
        else MockEmbeddingProvider()
    )
    rerank = (
        RemoteRerankProvider(
            settings.rerank_base_url,
            settings.rerank_api_key,
            settings.rerank_model,
            rerank_policy,
        )
        if settings.rerank_provider == "remote" and settings.rerank_base_url
        else MockRerankProvider()
    )
    return ProviderRegistry(generation=generation, embedding=embedding, rerank=rerank)


def build_generation_override(config: ProviderOverride) -> GenerationProvider:
    policy = _override_policy(config)
    return OpenAICompatibleGenerationProvider(
        config.base_url,
        config.api_key,
        config.model,
        policy,
        provider_name=config.provider_name,
    )


def build_embedding_override(config: ProviderOverride) -> EmbeddingProvider:
    return RemoteEmbeddingProvider(
        config.base_url,
        config.api_key,
        config.model,
        _override_policy(config),
        provider_name=config.provider_name,
    )


def build_rerank_override(config: ProviderOverride) -> RerankProvider:
    return RemoteRerankProvider(
        config.base_url,
        config.api_key,
        config.model,
        _override_policy(config),
        provider_name=config.provider_name,
    )


def _override_policy(config: ProviderOverride) -> ProviderPolicy:
    total_seconds = config.timeout_ms / 1000
    return ProviderPolicy(
        min(3.0, total_seconds),
        max(1.0, total_seconds - 1.0),
        total_seconds,
        config.retry_attempts,
    )


@lru_cache
def get_provider_registry() -> ProviderRegistry:
    return build_provider_registry(get_settings())
