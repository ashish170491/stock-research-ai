package com.ashish.stockresearch.agent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;

/**
 * The {@link ResearchSession} of every conversation. A session is read from the {@link SessionStore} the first time
 * it is needed and written back on every change, so a conversation picks up where it left off after a restart. The
 * sessions in use are also kept in memory; one idle for two hours is dropped from memory only - its stored state
 * and chat history stay until the retention period ends ({@link ConversationRetentionJob}).
 */
@Component
public class ResearchSessions {

    private static final Logger log = LoggerFactory.getLogger(ResearchSessions.class);

    static final Duration IDLE_TIMEOUT = Duration.ofHours(2);
    private static final int MAX_CONVERSATIONS = 1_000;

    private final SessionStore store;
    private final Cache<String, ResearchSession> sessions;

    @Autowired
    public ResearchSessions(SessionStore store) {
        this(store, Ticker.systemTicker());
    }

    ResearchSessions(SessionStore store, Ticker ticker) {
        this.store = store;
        this.sessions = Caffeine.newBuilder().ticker(ticker)
                .expireAfterAccess(IDLE_TIMEOUT)
                .maximumSize(MAX_CONVERSATIONS)
                .build();
    }

    public ResearchSession get(String conversationId) {
        return sessions.get(conversationId, id -> new ResearchSession(load(id), state -> save(id, state)));
    }

    /** Forgets a conversation's session, in memory and in the store. */
    public void forget(String conversationId) {
        sessions.invalidate(conversationId);
        store.delete(conversationId);
    }

    /** Drops sessions from memory only - for those the store has already deleted. */
    void evict(Collection<String> conversationIds) {
        sessions.invalidateAll(conversationIds);
    }

    private SessionState load(String conversationId) {
        try {
            return store.load(conversationId).orElseGet(SessionState::empty);
        } catch (RuntimeException ex) {
            log.warn("Could not load the session of conversation {}; starting it empty: {}", conversationId,
                    ex.getMessage());
            return SessionState.empty();
        }
    }

    /**
     * A store that cannot be written must not fail the answer the user is waiting for: the session stays correct in
     * memory and the next change writes it again.
     */
    private void save(String conversationId, SessionState state) {
        try {
            store.save(conversationId, state);
        } catch (RuntimeException ex) {
            log.warn("Could not store the session of conversation {}: {}", conversationId, ex.getMessage());
        }
    }
}
