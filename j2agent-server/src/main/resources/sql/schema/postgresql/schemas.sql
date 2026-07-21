DROP TABLE IF EXISTS api_key_info;
CREATE TABLE api_key_info
(
    api_key       varchar(64) PRIMARY KEY,
    api_key_owner varchar(64),
    create_time   bigint,
    expire_time   bigint
);

DROP TABLE IF EXISTS chat_context_record;
CREATE TABLE chat_context_record
(
    context_id         varchar(64)  NOT NULL,
    agent_id           varchar(64)  NOT NULL DEFAULT '',
    title              varchar(64)  NOT NULL,
    user_id            varchar(64),
    memory_version     int          NOT NULL DEFAULT 1,
    last_message_index int          NULL,
    update_time        bigint       NOT NULL,
    PRIMARY KEY (context_id, agent_id)
);

CREATE INDEX idx_chat_context_user_update_time ON chat_context_record (user_id, agent_id, update_time);

DROP TABLE IF EXISTS chat_context_item;
CREATE TABLE chat_context_item
(
    message_id        char(36)     NOT NULL,
    context_id        varchar(64)  NOT NULL,
    agent_id          varchar(64)  NOT NULL DEFAULT '',
    message_index     int          NOT NULL,
    chat_role         int          NOT NULL,
    content           text         NOT NULL,
    feedback          int,
    rag_infos         text,
    add_time          bigint       NOT NULL,
    meta_json         text         NULL,
    PRIMARY KEY (message_id)
);

CREATE INDEX idx_chat_context_item_ctx_agent ON chat_context_item (context_id, agent_id);
CREATE INDEX idx_chat_context_item_ctx_agent_msg_idx ON chat_context_item (context_id, agent_id, message_index);
CREATE INDEX idx_chat_context_item_add_time ON chat_context_item (context_id, agent_id, add_time);

DROP TABLE IF EXISTS llm_usage_record;
CREATE TABLE llm_usage_record
(
    id                      varchar(32)   NOT NULL,
    user_id                 varchar(64)   NULL,
    context_id              varchar(64)   NULL,
    agent_id                varchar(64)   NULL,
    turn_id                 varchar(64)   NULL,
    call_seq                int           NULL,
    call_kind               varchar(32)   NOT NULL,
    provider_type           varchar(64)   NULL,
    model_name              varchar(128)  NULL,
    input_tokens            int           NULL,
    output_tokens           int           NULL,
    total_tokens            int           NULL,
    billable_token_count    int           NULL,
    cached_input_tokens     int           NULL,
    cache_read_input_tokens int           NULL,
    cache_creation_input_tokens int       NULL,
    reasoning_output_tokens int           NULL,
    audio_input_tokens      int           NULL,
    audio_output_tokens     int           NULL,
    usage_status            varchar(32)   NOT NULL,
    native_usage_json       text          NULL,
    error_message           text          NULL,
    create_time             bigint        NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_llm_usage_turn ON llm_usage_record (user_id, context_id, agent_id, turn_id, call_seq);

DROP TABLE IF EXISTS app_user;
CREATE TABLE app_user
(
    id            char(32)    NOT NULL,
    username      varchar(64) NOT NULL,
    password_hash char(64)    NOT NULL,
    create_time   bigint,
    role          int,
    email         varchar(128) DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_user_email UNIQUE (email)
);

DROP TABLE IF EXISTS ai_properties;
CREATE TABLE ai_properties
(
    property_name  varchar(128) NOT NULL,
    property_value text         DEFAULT NULL,
    description    varchar(512) DEFAULT NULL,
    PRIMARY KEY (property_name)
);

DROP TABLE IF EXISTS api_provider_config;
CREATE TABLE api_provider_config
(
    id            char(32)     NOT NULL,
    api_type      varchar(64)  NOT NULL,
    config_name   varchar(128) NOT NULL,
    provider_type varchar(64)  NOT NULL,
    config_json   text         NOT NULL,
    enabled       smallint     NOT NULL DEFAULT 1,
    is_current    smallint     NOT NULL DEFAULT 0,
    description   varchar(512) DEFAULT NULL,
    create_time   bigint       DEFAULT NULL,
    update_time   bigint       DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_api_provider_type ON api_provider_config (api_type, provider_type);
CREATE INDEX idx_api_provider_current ON api_provider_config (api_type, is_current);

DROP TABLE IF EXISTS knowledge_text_chunk;
CREATE TABLE knowledge_text_chunk
(
    id              varchar(32)   NOT NULL,
    heading_path    varchar(2048) NOT NULL,
    text_chunk      text          NOT NULL,
    source_file     varchar(2048) NOT NULL,
    collection_name varchar(128)  NOT NULL,
    file_sha256     char(64)      NOT NULL,
    created_at      bigint        NOT NULL,
    updated_at      bigint        NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_source_file ON knowledge_text_chunk (source_file);
CREATE INDEX idx_collection ON knowledge_text_chunk (collection_name);

DROP TABLE IF EXISTS knowledge_source_file_hash;
CREATE TABLE knowledge_source_file_hash
(
    id              varchar(32)   NOT NULL,
    file_path       varchar(2048) NOT NULL,
    file_path_hash  char(64)      NOT NULL,
    file_sha256     char(64)      NOT NULL,
    info_json_hash  char(64)      NOT NULL,
    collection_name varchar(128)  NOT NULL,
    partition_names varchar(2048) DEFAULT NULL,
    knowledge_count int           NOT NULL DEFAULT 0,
    file_size_bytes bigint        NOT NULL DEFAULT 0,
    last_scan_time  bigint        NOT NULL,
    sync_status     varchar(32)   NOT NULL,
    created_at      bigint        NOT NULL,
    updated_at      bigint        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_file_path_hash UNIQUE (file_path_hash)
);

CREATE INDEX idx_sync_status ON knowledge_source_file_hash (sync_status);

DROP TABLE IF EXISTS simple_rag_collection_state;
CREATE TABLE simple_rag_collection_state
(
    id              varchar(32)  NOT NULL,
    collection_name varchar(128) NOT NULL,
    owner_agent_id  varchar(128) DEFAULT NULL,
    sync_status     varchar(32)  NOT NULL,
    document_count  int          NOT NULL DEFAULT 0,
    error_message   text         DEFAULT NULL,
    created_at      bigint       NOT NULL,
    updated_at      bigint       NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_simple_rag_collection UNIQUE (collection_name)
);

CREATE INDEX idx_simple_rag_sync_status ON simple_rag_collection_state (sync_status);

DROP TABLE IF EXISTS knowledge_repository;
CREATE TABLE knowledge_repository
(
    id                       varchar(32)   NOT NULL,
    repo_code                varchar(128)  NOT NULL,
    protocol                 varchar(32)   DEFAULT NULL,
    enabled                  boolean       NOT NULL DEFAULT true,
    update_interval_minutes  int           NOT NULL DEFAULT 60,
    status                   varchar(32)   NOT NULL,
    remote_url               varchar(2048) DEFAULT NULL,
    default_branch           varchar(256)  DEFAULT NULL,
    last_revision            varchar(256)  DEFAULT NULL,
    last_revision_message    text          DEFAULT NULL,
    last_revision_author     varchar(256)  DEFAULT NULL,
    last_revision_time       bigint        DEFAULT NULL,
    last_sync_time           bigint        DEFAULT NULL,
    last_error               text          DEFAULT NULL,
    protocol_config          jsonb         DEFAULT '{}'::jsonb,
    credential_config_cipher text          DEFAULT NULL,
    created_at               bigint        NOT NULL,
    updated_at               bigint        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_knowledge_repository_code UNIQUE (repo_code)
);

CREATE INDEX idx_knowledge_repository_protocol ON knowledge_repository (protocol);
CREATE INDEX idx_knowledge_repository_due ON knowledge_repository (enabled, status, last_sync_time);
