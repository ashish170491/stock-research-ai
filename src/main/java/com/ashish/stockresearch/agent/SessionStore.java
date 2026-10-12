package com.ashish.stockresearch.agent;

import java.util.Optional;

/** Where each conversation's {@link SessionState} is kept between turns and across restarts. */
public interface SessionStore {

    Optional<SessionState> load(String conversationId);

    /** Stores the state as the conversation's latest, and records now as its last activity. */
    void save(String conversationId, SessionState state);

    void delete(String conversationId);

    /** Keeps nothing: every conversation starts empty after a restart. For tests that need no database. */
    SessionStore NONE = new SessionStore() {
        @Override
        public Optional<SessionState> load(String conversationId) {
            return Optional.empty();
        }

        @Override
        public void save(String conversationId, SessionState state) {
        }

        @Override
        public void delete(String conversationId) {
        }
    };
}
