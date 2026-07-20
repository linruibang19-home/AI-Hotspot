-- RAG 索引需要能够重建 Chunk，同时必须保留已经交付给用户的历史引用快照。
-- quote_text / retrieval_score / support_status 已保存当时的证据，因此旧 Chunk
-- 被替换后将 chunk_id 置空，比级联删除历史 citation 更符合审计要求。
alter table research.citation
    drop constraint if exists citation_chunk_id_fkey;

alter table research.citation
    alter column chunk_id drop not null;

alter table research.citation
    add constraint citation_chunk_id_fkey
        foreign key (chunk_id) references knowledge.chunk(id) on delete set null;

comment on column research.citation.chunk_id is
    '当前知识分块ID；索引重建删除旧分块后可为空，历史证据由引用快照字段保留';
