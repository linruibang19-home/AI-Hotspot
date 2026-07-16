import hashlib
import math
import re

from .base import EmbeddingProvider, GenerationProvider, RankedDocument, RerankProvider

TOKEN_PATTERN = re.compile(r"[\w\u4e00-\u9fff]+", re.UNICODE)


class MockGenerationProvider(GenerationProvider):
    name = "mock"
    model = "mock-generation-v1"

    async def generate(self, prompt: str) -> str:
        summary = " ".join(prompt.strip().split())[:160]
        return f"[Mock Provider] 已接收请求：{summary}"


class MockEmbeddingProvider(EmbeddingProvider):
    name = "mock"
    model = "mock-embedding-v1"

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [self._vector(text) for text in texts]

    @staticmethod
    def _vector(text: str, dimensions: int = 16) -> list[float]:
        digest = hashlib.sha256(text.encode("utf-8")).digest()
        values = [(digest[index] / 127.5) - 1 for index in range(dimensions)]
        norm = math.sqrt(sum(value * value for value in values)) or 1
        return [round(value / norm, 8) for value in values]


class MockRerankProvider(RerankProvider):
    name = "mock"
    model = "mock-rerank-v1"

    async def rerank(
        self, query: str, documents: list[tuple[str, str]], top_n: int
    ) -> list[RankedDocument]:
        query_tokens = set(TOKEN_PATTERN.findall(query.lower()))
        ranked = []
        for document_id, text in documents:
            document_tokens = set(TOKEN_PATTERN.findall(text.lower()))
            union = query_tokens | document_tokens
            score = len(query_tokens & document_tokens) / len(union) if union else 0.0
            ranked.append(RankedDocument(id=document_id, score=round(score, 8)))
        return sorted(ranked, key=lambda item: (-item.score, item.id))[:top_n]
