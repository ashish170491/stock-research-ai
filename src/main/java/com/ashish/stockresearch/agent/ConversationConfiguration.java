package com.ashish.stockresearch.agent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ConversationConfiguration {

    /** How many messages of a conversation the model sees: ten exchanges. */
    static final int MEMORY_WINDOW = 20;

    /** In memory for now; a JdbcChatMemoryRepository would keep conversations across restarts. */
    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(MEMORY_WINDOW)
                .build();
    }
}
