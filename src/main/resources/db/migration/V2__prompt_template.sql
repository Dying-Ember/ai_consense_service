-- 提示词配置表：把原先硬编码在 DraftPrompts.java 里的提示词搬到数据库，
-- 支持在前端「提示词配置」页面直接编辑，无需重新打包。
--
-- 约定：
--   prompt_key      功能键，形如 drafting.discover / vetting.run
--   group_key       分组：drafting / vetting / advice
--   system_text     当前生效的 system prompt
--   user_template   当前生效的 user prompt 模板（含 %s 占位符）
--   default_*       出厂默认值，用于「恢复默认」

CREATE TABLE IF NOT EXISTS prompt_template (
    prompt_key              VARCHAR(64)  NOT NULL,
    group_key               VARCHAR(32)  NOT NULL,
    name_zh_hans            VARCHAR(255)          DEFAULT NULL,
    name_zh_hant            VARCHAR(255)          DEFAULT NULL,
    name_en                 VARCHAR(255)          DEFAULT NULL,
    description_zh_hans     TEXT,
    description_zh_hant     TEXT,
    description_en          TEXT,
    system_text             LONGTEXT,
    user_template           LONGTEXT,
    default_system_text     LONGTEXT,
    default_user_template   LONGTEXT,
    sort_order              INT          NOT NULL DEFAULT 0,
    updated_at              DATETIME(3)  NOT NULL,
    PRIMARY KEY (prompt_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
