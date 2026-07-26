alter table research.evidence_assessment
  add column if not exists entailment_status text not null default 'NOT_EVALUATED',
  add column if not exists entailment_score numeric(5,4);

alter table research.evidence_assessment
  drop constraint if exists ck_evidence_assessment_entailment,
  add constraint ck_evidence_assessment_entailment
    check (entailment_status in ('ENTAILED','PARTIAL','UNSUPPORTED','NOT_EVALUATED'));

alter table research.evidence_assessment
  drop constraint if exists ck_evidence_assessment_entailment_score,
  add constraint ck_evidence_assessment_entailment_score
    check (entailment_score is null or (entailment_score >= 0 and entailment_score <= 1));

alter table research.evidence_assessment
  drop constraint if exists ck_evidence_assessment_method,
  add constraint ck_evidence_assessment_method
    check (assessment_method in ('MODEL_AND_RULES','RULES','DEFAULT_RULES','ENTAILMENT_GUARD'));

create index if not exists ix_evidence_assessment_entailment
  on research.evidence_assessment(entailment_status, created_at desc);

comment on column research.evidence_assessment.entailment_status is
  '服务端 Claim-Evidence 校验结果；UNSUPPORTED 会阻止模型把证据标记为支持。';
comment on column research.evidence_assessment.entailment_score is
  '主张信号在原文证据中的覆盖比例，仅作为确定性安全门禁，不替代人工事实核验。';

update knowledge.evaluation_suite
set version='4.2',
    thresholds=thresholds || jsonb_build_object(
      'minimumStructuredOutputSamples',6,
      'minimumClaimEvidenceSamples',6,
      'claimEvidenceEntailmentRate',0.90,
      'citationProvenanceFailures',0
    )
where code='RAG_BASELINE_ZH';
