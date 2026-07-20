import json

from ai_hotspot_ai.content_analysis import analyze_content
from ai_hotspot_ai.providers.base import GenerationProvider
from ai_hotspot_ai.providers.factory import build_provider_registry
from ai_hotspot_ai.settings import Settings


class JsonProvider(GenerationProvider):
    name = "test"
    model = "test-json"

    async def generate(self, prompt: str) -> str:
        assert "不得补充未出现的事实" in prompt
        return json.dumps(
            {
                "titleZh": "新型 Agent 模型发布",
                "summaryZh": "官方发布了新的 Agent 模型。",
                "categoryCode": "MODEL_RELEASE",
                "tags": ["Agent 智能体", "模型发布"],
                "entities": [{"type": "COMPANY", "name": "OpenAI", "confidence": 95}],
                "factStatus": "CONFIRMED",
                "confidenceScore": 93,
                "recommendationReason": "官方一手发布。",
            },
            ensure_ascii=False,
        )


async def test_structured_analysis_has_configurable_quality_dimensions():
    result = await analyze_content(
        JsonProvider(),
        title="OpenAI releases new Agent model",
        summary="The official model release includes a new benchmark and inference API.",
        source_name="OpenAI",
        official_level="OFFICIAL",
        authority_score=98,
    )
    assert result.title_zh == "新型 Agent 模型发布"
    assert result.fact_status == "CONFIRMED"
    assert result.final_score == round(
        result.relevance_score * 0.34
        + result.quality_score * 0.36
        + 100 * 0.16
        + result.quality_dimensions["novelty"] * 0.07
        + result.quality_dimensions["impact"] * 0.07,
        2,
    )
    assert set(result.quality_dimensions) == {
        "completeness",
        "clarity",
        "sourceEvidence",
        "novelty",
        "impact",
        "informationDensity",
        "marketingPenalty",
        "rumorPenalty",
    }


async def test_unconfirmed_terms_are_labeled_and_downranked():
    result = await analyze_content(
        JsonProvider(),
        title="Rumor: model may launch",
        summary="This is unconfirmed.",
        source_name="Media",
        official_level="THIRD_PARTY",
        authority_score=70,
    )
    assert result.fact_status == "UNCONFIRMED"
    assert result.quality_score < 70


async def test_thin_non_ai_item_cannot_pass_quality_or_relevance():
    result = await analyze_content(
        JsonProvider(), title="手机促销", summary="手机促销", source_name="Media",
        official_level="THIRD_PARTY", authority_score=80,
    )
    assert result.relevance_score < 70
    assert result.quality_score < 60


def test_openai_compatible_requires_external_configuration():
    try:
        build_provider_registry(Settings(generation_provider="openai-compatible"))
    except RuntimeError as error:
        assert "GENERATION_BASE_URL" in str(error)
    else:
        raise AssertionError("missing provider configuration must fail fast")
