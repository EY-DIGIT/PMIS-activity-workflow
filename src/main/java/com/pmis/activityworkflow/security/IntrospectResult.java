package com.pmis.activityworkflow.security;

/**
 * Parsed result from the upstream token introspect call.
 *
 * @param active   true only when upstream says active=true AND expired=false
 * @param userId   upstream userId (UUID string)
 * @param username upstream username/sub
 * @param email    upstream email
 */
public record IntrospectResult(boolean active, String userId, String username, String email) {

    /** Sentinel returned on any network or parse failure. */
    static IntrospectResult inactive() {
        return new IntrospectResult(false, null, null, null);
    }
}
