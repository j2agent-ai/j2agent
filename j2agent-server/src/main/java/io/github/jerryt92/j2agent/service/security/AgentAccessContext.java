package io.github.jerryt92.j2agent.service.security;

import io.github.jerryt92.j2agent.model.security.UserContextBo;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime-only identities bound by AiAgent, never reconstructed from persisted message metadata. */
public final class AgentAccessContext {
    private static final ConcurrentHashMap<String, UserContextBo> USERS = new ConcurrentHashMap<>();
    private AgentAccessContext() { }
    public static void bind(String conversation, UserContextBo user) {
        USERS.put(conversation, ResourceAccessService.requireIdentity(user));
    }
    public static UserContextBo get(String conversation) { return conversation == null ? null : USERS.get(conversation); }
    public static void clear(String conversation) { USERS.remove(conversation); }
}
