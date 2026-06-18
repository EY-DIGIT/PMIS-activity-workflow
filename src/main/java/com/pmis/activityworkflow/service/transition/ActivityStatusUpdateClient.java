package com.pmis.activityworkflow.service.transition;

import com.pmis.activityworkflow.config.ActivityStatusUpdateProperties;
import com.pmis.activityworkflow.exception.InvalidTransitionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Calls {@code PATCH /projects/api/v3/activities/{activityId}} with
 * {@code {"status":"completed","activityStarted":true}} when the workflow
 * transitions to the ACTIVITYCOMPLETED state.
 *
 * <p>The caller's Authorization header is forwarded verbatim.</p>
 */
@Service
@Slf4j
public class ActivityStatusUpdateClient {

    private static final String COMPLETED_BODY = "{\"status\":\"completed\",\"activityStarted\":true}";

    private final ActivityStatusUpdateProperties props;
    private final RestClient activityStatusUpdateRestClient;

    public ActivityStatusUpdateClient(
            ActivityStatusUpdateProperties props,
            @Qualifier("activityStatusUpdateRestClient") RestClient activityStatusUpdateRestClient) {
        this.props = props;
        this.activityStatusUpdateRestClient = activityStatusUpdateRestClient;
    }

    /**
     * PATCHes the upstream activity to {@code status=completed}.
     * Best-effort — logs errors but does not fail the surrounding transition.
     */
    public void markCompleted(String activityId) {
        if (!props.isEnabled()) {
            log.info("Activity status update disabled; skipping PATCH for activityId={}", activityId);
            return;
        }

        String auth = currentAuthHeader();
        String url  = props.getUrlTemplate().replace("{activityId}", activityId);

        try {
            byte[] rawBytes = activityStatusUpdateRestClient.patch()
                    .uri(url)
                    .header("Authorization", auth != null ? auth : "")
                    .header("accept", "application/json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(COMPLETED_BODY)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        String responseBody = new String(resp.getBody().readAllBytes(),
                                StandardCharsets.UTF_8);
                        log.error("Activity status update PATCH {} returned {}: {}",
                                url, resp.getStatusCode(), responseBody);
                        throw new InvalidTransitionException(
                                "Activity status update failed (" + resp.getStatusCode() + "): "
                                        + responseBody);
                    })
                    .body(byte[].class);

            String raw = rawBytes != null ? new String(rawBytes, StandardCharsets.UTF_8) : "{}";
            log.info("Activity {} marked completed upstream. Response: {}", activityId, raw);

        } catch (Exception ex) {
            log.error("Activity status update PATCH failed for activityId={}: {}",
                    activityId, ex.getMessage());
        }
    }

    private String currentAuthHeader() {
        return Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(ServletRequestAttributes.class::cast)
                .map(attrs -> attrs.getRequest().getHeader("Authorization"))
                .orElse(null);
    }
}
