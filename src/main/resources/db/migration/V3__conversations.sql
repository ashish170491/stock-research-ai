-- Conversations survive a restart: the message window (Spring AI's JdbcChatMemoryRepository layout, with the id
-- as long as AiController allows) and the session state AiService keeps beside it - the companies discussed,
-- chosen topics, pending questions and a research plan paused for "continue". Both are deleted after
-- app.conversations.retention of inactivity (ConversationRetentionJob).
CREATE TABLE spring_ai_chat_memory (
    conversation_id VARCHAR(64) NOT NULL,
    content         TEXT        NOT NULL,
    type            VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp"     TIMESTAMP   NOT NULL,
    sequence_id     BIGINT      NOT NULL
);
CREATE INDEX spring_ai_chat_memory_conversation_id_timestamp_idx ON spring_ai_chat_memory (conversation_id, "timestamp");
CREATE INDEX spring_ai_chat_memory_conversation_id_sequence_id_idx ON spring_ai_chat_memory (conversation_id, sequence_id);

CREATE TABLE conversation_session (
    conversation_id VARCHAR(64)              PRIMARY KEY,
    state           JSONB                    NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX conversation_session_updated_idx ON conversation_session (updated_at);
