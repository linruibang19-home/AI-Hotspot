FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY pyproject.toml ./
RUN mkdir -p src/ai_hotspot_ai \
    && touch src/ai_hotspot_ai/__init__.py \
    && pip install --upgrade pip \
    && pip install "hatchling>=1.27.0" setuptools wheel \
    && pip install --no-build-isolation .

COPY src ./src
RUN pip install --no-deps --no-build-isolation .

RUN useradd --create-home --uid 10001 appuser
USER appuser

EXPOSE 8000
CMD ["uvicorn", "ai_hotspot_ai.main:app", "--host", "0.0.0.0", "--port", "8000", "--http", "h11"]
