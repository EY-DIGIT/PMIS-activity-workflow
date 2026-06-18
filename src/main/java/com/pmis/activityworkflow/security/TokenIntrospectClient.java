package com.pmis.activityworkflow.security;

import com.pmis.activityworkflow.config.IntrospectProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

/**
 * Calls POST /users/api/v3/users/introspect to validate an access token.
 *
 * <p>Returns {@link IntrospectResult} with {@code active=false} on any
 * failure (network error, upstream 4xx/5xx, malformed JSON) so the caller
 * can treat any non-active result as 401.</p>
 */
@Component
@Slf4j
public class TokenIntrospectClient {

    private final IntrospectProperties props;
    private final RestClient introspectRestClient;
    private final ObjectMapper objectMapper;

    public TokenIntrospectClient(
            IntrospectProperties props,
            @Qualifier("introspectRestClient") RestClient introspectRestClient,
            ObjectMapper objectMapper) {
        this.props = props;
        this.introspectRestClient = introspectRestClient;
        this.objectMapper = objectMapper;
    }

    /**
     * @param accessToken  raw Bearer token (without the "Bearer " prefix)
     * @return the parsed result; never null — {@code active=false} on any error
     */
    public IntrospectResult introspect(String accessToken) {
        try {
            String body = objectMapper.writeValueAsString(
                    java.util.Map.of("access_token", accessToken));

            byte[] rawBytes = introspectRestClient.post()
                    .uri(props.getUrlTemplate())
                    .header("accept", "application/json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        String rb = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        log.warn("Introspect API returned {}: {}", resp.getStatusCode(), rb);
                        throw new RuntimeException("Introspect upstream error: " + resp.getStatusCode());
                    })
                    .body(byte[].class);

            String raw = rawBytes != null ? new String(rawBytes, StandardCharsets.UTF_8) : "{}";
            return parse(raw);

        } catch (Exception ex) {
            log.warn("Token introspection failed: {}", ex.getMessage());
            return IntrospectResult.inactive();
        }
    }

    private IntrospectResult parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode data = root.has("data") && !root.get("data").isNull()
                    ? root.get("data") : root;

            boolean active  = data.path("active").asBoolean(false);
            boolean expired = data.path("expired").asBoolean(true);
            String  userId  = data.path("userId").asText(null);
            String  username = data.path("username").asText(null);
            String  email   = data.path("email").asText(null);

            return new IntrospectResult(active && !expired, userId, username, email);
        } catch (Exception ex) {
            log.warn("Failed to parse introspect response: {}", ex.getMessage());
            return IntrospectResult.inactive();
        }
    }
}
