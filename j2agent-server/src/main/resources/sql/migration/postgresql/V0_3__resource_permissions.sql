ALTER TABLE knowledge_repository ADD COLUMN creator_user_id char(32) REFERENCES app_user(id) ON DELETE SET NULL;
ALTER TABLE knowledge_repository ADD COLUMN is_public boolean NOT NULL DEFAULT false;
UPDATE knowledge_repository SET is_public = true;
CREATE TABLE agent_access_config (
    agent_id varchar(128) PRIMARY KEY,
    is_public boolean NOT NULL DEFAULT false,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL
);
CREATE TABLE user_agent_permission (
    id varchar(32) PRIMARY KEY,
    user_id char(32) NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    agent_id varchar(128) NOT NULL REFERENCES agent_access_config(agent_id) ON DELETE CASCADE,
    permission_level smallint NOT NULL CHECK (permission_level = 2),
    expires_at bigint,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL,
    UNIQUE(user_id, agent_id)
);
CREATE INDEX idx_user_agent_permission_resource ON user_agent_permission(agent_id);
CREATE TABLE user_knowledge_permission (
    id varchar(32) PRIMARY KEY,
    user_id char(32) NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    knowledge_repository_id varchar(32) NOT NULL REFERENCES knowledge_repository(id) ON DELETE CASCADE,
    permission_level smallint NOT NULL CHECK (permission_level IN (1, 2)),
    expires_at bigint,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL,
    UNIQUE(user_id, knowledge_repository_id)
);
CREATE INDEX idx_user_knowledge_permission_resource ON user_knowledge_permission(knowledge_repository_id);
CREATE INDEX idx_knowledge_repository_creator ON knowledge_repository(creator_user_id);
CREATE TABLE knowledge_repository_task (
    id varchar(32) PRIMARY KEY,
    repository_id varchar(32) NOT NULL,
    repo_code varchar(128) NOT NULL,
    user_id varchar(32),
    operation varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    error_message text,
    created_at bigint NOT NULL,
    updated_at bigint NOT NULL
);
CREATE INDEX idx_repository_task ON knowledge_repository_task(repository_id, created_at);
