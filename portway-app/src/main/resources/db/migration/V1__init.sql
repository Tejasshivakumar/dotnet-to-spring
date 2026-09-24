-- Portway schema. One migration job owns everything else; deleting a job
-- deletes its files, methods and findings with it.

CREATE TABLE migration_job (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL,   -- PENDING RUNNING COMPLETED FAILED
    current_stage   VARCHAR(32),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    options         JSONB,
    stats           JSONB,
    error_message   TEXT,
    verifier        VARCHAR(64),
    compile_status  VARCHAR(16)  NOT NULL DEFAULT 'NOT_RUN'   -- PASS FAIL NOT_RUN
);

-- Per-stage timing, for the progress tracker.
CREATE TABLE job_stage (
    id              BIGSERIAL PRIMARY KEY,
    job_id          UUID NOT NULL REFERENCES migration_job(id) ON DELETE CASCADE,
    stage           VARCHAR(32)  NOT NULL,
    started_at      TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,
    detail          TEXT
);

CREATE TABLE source_file (
    id              UUID PRIMARY KEY,
    job_id          UUID NOT NULL REFERENCES migration_job(id) ON DELETE CASCADE,
    path            VARCHAR(1024) NOT NULL,
    content         TEXT NOT NULL,
    detected_role   VARCHAR(32)
);

CREATE TABLE generated_file (
    id                UUID PRIMARY KEY,
    job_id            UUID NOT NULL REFERENCES migration_job(id) ON DELETE CASCADE,
    source_file_id    UUID REFERENCES source_file(id) ON DELETE SET NULL,
    path              VARCHAR(1024) NOT NULL,
    category          VARCHAR(32)  NOT NULL,   -- CONTROLLER ENTITY REPOSITORY SERVICE DTO CONFIG MODEL BUILD
    position          INT          NOT NULL,   -- generation order, which is dependency order
    original_content  TEXT         NOT NULL,   -- as generated
    content           TEXT         NOT NULL,   -- as reviewed, possibly edited
    strategy          VARCHAR(32)  NOT NULL,
    confidence        NUMERIC(3,2) NOT NULL,
    compile_status    VARCHAR(16)  NOT NULL,   -- PASS FAIL NOT_RUN
    review_status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING'   -- PENDING ACCEPTED REJECTED
);

CREATE TABLE migrated_method (
    id                UUID PRIMARY KEY,
    job_id            UUID NOT NULL REFERENCES migration_job(id) ON DELETE CASCADE,
    generated_file_id UUID REFERENCES generated_file(id) ON DELETE CASCADE,
    method_key        VARCHAR(512) NOT NULL,
    java_signature    VARCHAR(1024) NOT NULL,
    strategy          VARCHAR(32)  NOT NULL,
    tier              VARCHAR(8),
    reason            TEXT,
    source_path       VARCHAR(1024),
    source_line       INT,
    source_end_line   INT
);

CREATE TABLE finding (
    id                UUID PRIMARY KEY,
    job_id            UUID NOT NULL REFERENCES migration_job(id) ON DELETE CASCADE,
    generated_file_id UUID REFERENCES generated_file(id) ON DELETE CASCADE,
    severity          VARCHAR(8)   NOT NULL,
    code              VARCHAR(64)  NOT NULL,
    message           TEXT         NOT NULL,
    source_path       VARCHAR(1024),
    source_line       INT,
    generated_line    INT
);

-- LLM responses keyed by a hash of the exact prompt. Re-runs are free and the
-- demo is instant; it is also the honest answer to "is this expensive to run".
CREATE TABLE ai_cache (
    prompt_hash     VARCHAR(64) PRIMARY KEY,
    model           VARCHAR(128) NOT NULL,
    response_json   JSONB        NOT NULL,
    input_tokens    INT,
    output_tokens   INT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_job_created          ON migration_job(created_at DESC);
CREATE INDEX idx_job_stage_job        ON job_stage(job_id);
CREATE INDEX idx_source_file_job      ON source_file(job_id);
CREATE INDEX idx_generated_file_job   ON generated_file(job_id, position);
CREATE INDEX idx_method_job           ON migrated_method(job_id);
CREATE INDEX idx_finding_job          ON finding(job_id, severity);
CREATE INDEX idx_finding_file         ON finding(generated_file_id);
