from .base import EmbeddingProvider, RankedDocument, RerankProvider
from .resilience import ProviderError, ProviderPolicy, post_json


class RemoteEmbeddingProvider(EmbeddingProvider):
    name = "remote"

    def __init__(
        self,
        base_url: str,
        api_key: str | None,
        model: str,
        policy: ProviderPolicy,
        provider_name: str = "remote",
    ) -> None:
        self.name = provider_name
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.policy = policy

    async def embed(self, texts: list[str]) -> list[list[float]]:
        headers = {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}
        data = await post_json(
            "EMBEDDING",
            f"{self.base_url}/embeddings",
            headers=headers,
            payload={"model": self.model, "input": texts},
            policy=self.policy,
        )
        try:
            rows = sorted(data["data"], key=lambda row: row.get("index", 0))
            return [list(map(float, row["embedding"])) for row in rows]
        except (KeyError, TypeError, ValueError) as error:
            raise ProviderError(
                "EMBEDDING",
                "EMBEDDING_INVALID_RESPONSE",
                "Embedding response is missing numeric vectors",
                retryable=False,
                attempts=1,
            ) from error


class RemoteRerankProvider(RerankProvider):
    name = "remote"

    def __init__(
        self,
        base_url: str,
        api_key: str | None,
        model: str,
        policy: ProviderPolicy,
        provider_name: str = "remote",
    ) -> None:
        self.name = provider_name
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.policy = policy

    async def rerank(
        self,
        query: str,
        documents: list[tuple[str, str]],
        top_n: int,
    ) -> list[RankedDocument]:
        headers = {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}
        data = await post_json(
            "RERANK",
            f"{self.base_url}/rerank",
            headers=headers,
            payload={
                "model": self.model,
                "query": query,
                "documents": [text for _, text in documents],
                "top_n": top_n,
            },
            policy=self.policy,
        )
        try:
            results = data.get("results", data.get("data", []))
            ranked = []
            for row in results:
                index = int(row.get("index", row.get("document_index", 0)))
                if 0 <= index < len(documents):
                    ranked.append(
                        RankedDocument(
                            id=documents[index][0],
                            score=float(row.get("relevance_score", row.get("score", 0))),
                        )
                    )
            return ranked[:top_n]
        except (TypeError, ValueError) as error:
            raise ProviderError(
                "RERANK",
                "RERANK_INVALID_RESPONSE",
                "Rerank response contains invalid result rows",
                retryable=False,
                attempts=1,
            ) from error
