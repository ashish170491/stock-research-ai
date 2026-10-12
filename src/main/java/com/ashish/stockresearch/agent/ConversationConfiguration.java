package com.ashish.stockresearch.agent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ConversationConfiguration {

    /** How many messages of a conversation the model sees: ten exchanges. */
    static final int MEMORY_WINDOW = 20;

    /**
     * The message window over Spring AI's JdbcChatMemoryRepository (table spring_ai_chat_memory, created by Flyway),
     * so a conversation's history survives a restart. AiService still reads and writes it by hand, after its checks
     * (S1-2, S1-8); ConversationRetentionJob deletes it after the retention period.
     */
    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(MEMORY_WINDOW)
                .build();
    }
}
