import os


# Unit tests must be deterministic even when invoked from the repository root,
# where pydantic-settings can otherwise discover the developer's real .env.
os.environ["GENERATION_PROVIDER"] = "mock"
os.environ["EMBEDDING_PROVIDER"] = "mock"
os.environ["RERANK_PROVIDER"] = "mock"
