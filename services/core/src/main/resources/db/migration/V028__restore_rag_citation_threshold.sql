update knowledge.evaluation_suite
set thresholds=jsonb_set(thresholds,'{citationSupport}','0.95'::jsonb),
    version='3.1'
where code='RAG_BASELINE_ZH';
