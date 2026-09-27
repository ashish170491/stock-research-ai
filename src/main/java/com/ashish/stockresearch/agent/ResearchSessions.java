package com.ashish.stockresearch.agent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The {@link ResearchSession} of every live conversation. A conversation left idle for two hours
 * is forgotten, and its chat history with it, so memory stays bounded however many are started.
 */
@Component
public class ResearchSessions {

    static final Duration IDLE_TIMEOUT = Duration.ofHours(2);
    private static final int MAX_CONVERSATIONS = 1_000;

    private final Cache<String, ResearchSession> sessions;

    public ResearchSessions(ChatMemory chatMemory) {
        this.sessions = Caffeine.newBuilder()
                .expireAfterAccess(IDLE_TIMEOUT)
                .maximumSize(MAX_CONVERSATIONS)
                .removalListener((String id, ResearchSession session, RemovalCause cause) -> {
                    if (id != null && cause != RemovalCause.REPLACED) {
                        chatMemory.clear(id);
                    }
                })
                .build();
    }

    public ResearchSession get(String conversationId) {
        return sessions.get(conversationId, id -> new ResearchSession());
    }
}
