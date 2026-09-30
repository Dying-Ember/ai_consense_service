-- ConSense CAC Solution 路 鍒濆琛ㄧ粨鏋?
-- 瀛楃闆嗙粺涓€ utf8mb4锛屼究浜庝繚瀛樼畝绻佽嫳涓夎涓?OCR 鍘熸枃

CREATE TABLE IF NOT EXISTS project (
    id                      VARCHAR(64)  NOT NULL,
    name_zh_hans            VARCHAR(512)          DEFAULT NULL,
    name_zh_hant            VARCHAR(512)          DEFAULT NULL,
    name_en                 VARCHAR(512)          DEFAULT NULL,
    contract_no             VARCHAR(128)          DEFAULT NULL,
    package_ref             VARCHAR(128)          DEFAULT NULL,
    output_reference_file   VARCHAR(512)          DEFAULT NULL,
    pages                   VARCHAR(16)           DEFAULT NULL,
    ntt_range               VARCHAR(128)          DEFAULT NULL,
    sct_range               VARCHAR(128)          DEFAULT NULL,
    scc_range               VARCHAR(128)          DEFAULT NULL,
    specification           VARCHAR(512)          DEFAULT NULL,
    title_lines_json        TEXT,
    created_at              DATETIME(3)  NOT NULL,
    updated_at              DATETIME(3)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS source_document (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    project_id      VARCHAR(64)  NOT NULL,
    category        VARCHAR(32)  NOT NULL,
    file_key        VARCHAR(32)           DEFAULT NULL,
    file_name       VARCHAR(512) NOT NULL,
    content_type    VARCHAR(160)          DEFAULT NULL,
    size_bytes      BIGINT                DEFAULT 0,
    storage_path    VARCHAR(1024)         DEFAULT NULL,
    parse_status    VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    parse_message   VARCHAR(1024)         DEFAULT NULL,
    page_count      INT                   DEFAULT 0,
    ocr_used        TINYINT(1)   NOT NULL DEFAULT 0,
    text_content    LONGTEXT,
    created_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_source_doc_project (project_id, category),
    KEY idx_source_doc_file_key (project_id, file_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS draft_variable (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    project_id          VARCHAR(64)  NOT NULL,
    var_key             VARCHAR(64)  NOT NULL,
    scope               VARCHAR(16)  NOT NULL,
    file_key            VARCHAR(16)           DEFAULT NULL,
    label_zh_hans       VARCHAR(512)          DEFAULT NULL,
    label_zh_hant       VARCHAR(512)          DEFAULT NULL,
    label_en            VARCHAR(512)          DEFAULT NULL,
    action              VARCHAR(16)           DEFAULT NULL,
    value_text          TEXT,
    choice              VARCHAR(255)          DEFAULT NULL,
    options_json        TEXT,
    confirmed           TINYINT(1)   NOT NULL DEFAULT 0,
    confirmed_from      VARCHAR(16)           DEFAULT NULL,
    source_ref          VARCHAR(512)          DEFAULT NULL,
    result_text         TEXT,
    note_text           TEXT,
    linked_base         VARCHAR(64)           DEFAULT NULL,
    derived_from        VARCHAR(128)          DEFAULT NULL,
    affects             VARCHAR(128)          DEFAULT NULL,
    kind                VARCHAR(16)           DEFAULT NULL,
    cols_json           TEXT,
    sort_order          INT          NOT NULL DEFAULT 0,
    updated_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_draft_var (project_id, var_key),
    KEY idx_draft_var_scope (project_id, scope, file_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS draft_document (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    project_id      VARCHAR(64)  NOT NULL,
    file_key        VARCHAR(16)  NOT NULL,
    title           VARCHAR(512)          DEFAULT NULL,
    content         LONGTEXT,
    `generated`       TINYINT(1)   NOT NULL DEFAULT 0,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_draft_doc (project_id, file_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS vetting_finding (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    project_id          VARCHAR(64)  NOT NULL,
    code                VARCHAR(32)  NOT NULL,
    types               VARCHAR(160)          DEFAULT NULL,
    group_key           VARCHAR(32)  NOT NULL,
    scope               VARCHAR(16)           DEFAULT NULL,
    severity            VARCHAR(16)           DEFAULT NULL,
    status              VARCHAR(32)  NOT NULL DEFAULT 'Open',
    title_zh_hans       TEXT,
    title_zh_hant       TEXT,
    title_en            TEXT,
    body_zh_hans        TEXT,
    body_zh_hant        TEXT,
    body_en             TEXT,
    impact_zh_hans      TEXT,
    impact_zh_hant      TEXT,
    impact_en           TEXT,
    suggestion_zh_hans  TEXT,
    suggestion_zh_hant  TEXT,
    suggestion_en       TEXT,
    pattern_text        VARCHAR(512)          DEFAULT NULL,
    refs                VARCHAR(512)          DEFAULT NULL,
    location            VARCHAR(512)          DEFAULT NULL,
    expected            VARCHAR(512)          DEFAULT NULL,
    evidence_id         VARCHAR(64)           DEFAULT NULL,
    file_key            VARCHAR(16)           DEFAULT NULL,
    page_no             VARCHAR(16)           DEFAULT NULL,
    bucket_key          VARCHAR(64)           DEFAULT NULL,
    created_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_finding_code (project_id, code),
    KEY idx_finding_group (project_id, group_key),
    KEY idx_finding_file (project_id, file_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    project_id      VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    title           VARCHAR(255)          DEFAULT NULL,
    content         LONGTEXT,
    unknown_scope   VARCHAR(32)           DEFAULT NULL,
    citations_json  TEXT,
    evidence_id     VARCHAR(64)           DEFAULT NULL,
    created_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_chat_project (project_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS skill_doc (
    id              VARCHAR(64)  NOT NULL,
    code            VARCHAR(64)           DEFAULT NULL,
    name_zh_hans    VARCHAR(255)          DEFAULT NULL,
    name_zh_hant    VARCHAR(255)          DEFAULT NULL,
    name_en         VARCHAR(255)          DEFAULT NULL,
    purpose_zh_hans TEXT,
    purpose_zh_hant TEXT,
    purpose_en      TEXT,
    content_json    LONGTEXT,
    sort_order      INT          NOT NULL DEFAULT 0,
    updated_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS evidence_chunk (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    project_id      VARCHAR(64)  NOT NULL,
    document_id     BIGINT                DEFAULT NULL,
    chunk_index     INT          NOT NULL DEFAULT 0,
    file_label      VARCHAR(512)          DEFAULT NULL,
    page_no         VARCHAR(16)           DEFAULT NULL,
    anchor          VARCHAR(255)          DEFAULT NULL,
    content         LONGTEXT,
    point_id        VARCHAR(64)           DEFAULT NULL,
    created_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_chunk_project (project_id, chunk_index),
    KEY idx_chunk_doc (document_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
