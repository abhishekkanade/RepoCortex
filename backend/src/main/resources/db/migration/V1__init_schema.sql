CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE repositories (
    id                 uuid PRIMARY KEY,
    owner              varchar(100)  NOT NULL,
    name               varchar(100)  NOT NULL,
    full_name          varchar(201)  NOT NULL UNIQUE,
    description        varchar(1000),
    language           varchar(100),
    stars              integer       NOT NULL DEFAULT 0,
    default_branch     varchar(255),
    commit_sha         varchar(40),
    indexed_commit_sha varchar(40),
    status             varchar(20)   NOT NULL,
    files_total        integer       NOT NULL DEFAULT 0,
    files_processed    integer       NOT NULL DEFAULT 0,
    chunk_count        integer       NOT NULL DEFAULT 0,
    error_message      varchar(2000),
    summary            text,
    file_tree          text,
    indexed_at         timestamptz,
    created_at         timestamptz   NOT NULL,
    updated_at         timestamptz   NOT NULL
);

CREATE INDEX idx_repositories_status_indexed_at ON repositories (status, indexed_at DESC);

CREATE TABLE code_chunks (
    id           bigserial PRIMARY KEY,
    repo_id      uuid         NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    commit_sha   varchar(40)  NOT NULL,
    file_path    text         NOT NULL,
    start_line   integer      NOT NULL,
    end_line     integer      NOT NULL,
    language     varchar(50),
    content      text         NOT NULL,
    content_hash varchar(64)  NOT NULL,
    embedding    vector(1536) NOT NULL,
    -- 'simple' config: no English stemming, so identifiers stay as written
    tsv          tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED
);

CREATE INDEX idx_code_chunks_repo_commit ON code_chunks (repo_id, commit_sha);
CREATE INDEX idx_code_chunks_embedding ON code_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_code_chunks_tsv ON code_chunks USING gin (tsv);

CREATE TABLE embedding_cache (
    content_hash varchar(64)  PRIMARY KEY,
    embedding    vector(1536) NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now()
);
