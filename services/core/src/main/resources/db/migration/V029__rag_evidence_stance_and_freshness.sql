create table research.evidence_assessment (
    citation_id uuid primary key references research.citation(id) on delete cascade,
    claim_text text not null,
    evidence_stance text not null,
    freshness_status text not null,
    assessment_reason text not null,
    assessment_method text not null,
    created_at timestamptz not null default now(),
    constraint ck_evidence_assessment_stance
        check (evidence_stance in ('SUPPORTS','REFUTES','UNVERIFIED')),
    constraint ck_evidence_assessment_freshness
        check (freshness_status in ('CURRENT','OUTDATED','UNKNOWN')),
    constraint ck_evidence_assessment_method
        check (assessment_method in ('MODEL_AND_RULES','RULES','DEFAULT_RULES'))
);

create index ix_evidence_assessment_stance
    on research.evidence_assessment(evidence_stance, freshness_status);

comment on table research.evidence_assessment is
    'RAG 引用对应的事实立场与时效快照；与 citation.support_status 的引用质量语义分离。';
comment on column research.evidence_assessment.claim_text is
    '该证据所支持、反驳或尚未证实的简短主张；相同主张用于识别冲突证据组。';
comment on column research.evidence_assessment.assessment_method is
    'MODEL_AND_RULES 表示模型立场加服务端规则，RULES 表示事实/时间规则覆盖，DEFAULT_RULES 表示安全默认。';
