package com.pmis.activityworkflow.service.users;

import com.pmis.activityworkflow.config.UserDetailsProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Looks up a user by uuid via the upstream user API.
 *
 * <p>Used by the rejection-notification flow to resolve the original
 * SUBMIT actor's uuid into a real email + display name. Forwards the
 * caller's Authorization header verbatim.</p>
 */
@Service
@Slf4j
public class UserDetailsClient {

    private final UserDetailsProperties props;
    private final RestClient userDetailsRestClient;
    private final ObjectMapper objectMapper;

    public UserDetailsClient(
            UserDetailsProperties props,
            @Qualifier("userDetailsRestClient") RestClient userDetailsRestClient,
            ObjectMapper objectMapper) {
        this.props = props;
        this.userDetailsRestClient = userDetailsRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetch user details by uuid. Returns null on any failure (404, auth
     * error, blank uuid) — callers should treat null as "couldn't resolve"
     * and degrade gracefully.
     */
    public UserDetails fetch(String userUuid) {
        if (!StringUtils.hasText(userUuid)) return null;

        String auth = currentAuthHeader();
        if (!StringUtils.hasText(auth)) {
            log.debug("No Authorization on inbound request - cannot fetch user {}", userUuid);
            return null;
        }

        String url = props.getUrlTemplate().replace("{userId}", userUuid);
        try {
            String raw = userDetailsRestClient.get()
                    .uri(url)
                    .header("Authorization", auth)
                    .header("accept", "application/json")
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(raw);
            JsonNode data = root.has("data") && !root.get("data").isNull() ? root.get("data") : root;
            return objectMapper.treeToValue(data, UserDetails.class);
        } catch (Exception ex) {
            log.warn("User-lookup failed for {}: {}", userUuid, ex.getMessage());
            return null;
        }
    }

    private String currentAuthHeader() {
        return Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(ServletRequestAttributes.class::cast)
                .map(attrs -> attrs.getRequest().getHeader("Authorization"))
                .orElse(null);
    }

    /** Subset of upstream user response that the rejection flow needs. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserDetails {
        private String id;
        private String login;
        private String email;

        @JsonProperty("first_name")
        private String firstName;

        @JsonProperty("last_name")
        private String lastName;

        @JsonProperty("full_name")
        private String fullName;

        private String division;

        @JsonProperty("division_label")
        private String divisionLabel;

        /**
         * Best human-readable name for emails:
         * full_name > "first last" > login > uuid fragment.
         */
        public String bestDisplayName() {
            if (StringUtils.hasText(fullName))  return fullName;
            String composed = ((firstName == null ? "" : firstName) + " "
                            + (lastName  == null ? "" : lastName)).trim();
            if (!composed.isEmpty()) return composed;
            if (StringUtils.hasText(login)) return login;
            return id == null ? "user" : id.substring(0, Math.min(8, id.length()));
        }
    }
}
