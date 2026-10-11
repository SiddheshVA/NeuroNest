package com.neuronest.backend.session;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SessionAccessService {

    public void checkAccess(Session session, UUID currentUserId) {
        UUID owner = session.getUserId();
        if (owner != null && !owner.equals(currentUserId)) {
            throw new SessionAccessDeniedException("You do not have access to this session.");
        }
    }
}