package com.pmis.activityworkflow.service.assignments;

import com.pmis.activityworkflow.config.AssignmentsProperties;
import com.pmis.activityworkflow.exception.InvalidTransitionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Wraps the upstream "activity assignments" REST API.
 *
 * <p>Auth model — the caller's {@code Authorization} header is forwarded
 * verbatim. If there's no current servlet request (e.g. background job)
 * the caller must pass the bearer token explicitly via the overload.</p>
 */
@Service
@Slf4j
public class ActivityAssignmentsClient {

    private final AssignmentsProperties props;
    private final RestClient assignmentsRestClient;

    public ActivityAssignmentsClient(
            AssignmentsProperties props,
            @Qualifier("assignmentsRestClient") RestClient assignmentsRestClient) {
        this.props = props;
        this.assignmentsRestClient = assignmentsRestClient;
    }

    /**
     * Fetch assignments for one activity. Uses the Authorization header
     * from the current HTTP request (forwarded to the upstream API).
     */
    public AssignmentData fetch(String activityId) {
        return fetch(activityId, currentAuthHeader());
    }

    /**
     * Fetch assignments with an explicit Authorization header — useful
     * when the call is happening outside an HTTP request (e.g. scheduled
     * job, internal pipeline).
     *
     * @param authHeader the full header value, including the "Bearer " prefix
     */
    public AssignmentData fetch(String activityId, String authHeader) {
        if (!props.isEnabled()) {
            throw new InvalidTransitionException(
                    "Assignments API is disabled (app.assignments.enabled=false)");
        }
        if (!StringUtils.hasText(activityId)) {
            throw new InvalidTransitionException("activityId is required");
        }
        if (!StringUtils.hasText(authHeader)) {
            throw new InvalidTransitionException(
                    "Authorization header is required to call the assignments API");
        }

        String url = props.getUrlTemplate().replace("{activityId}", activityId);
        log.debug("Fetching assignments for activity={} from {}", activityId, url);

        try {
            AssignmentResponse body = assignmentsRestClient.get()
                    .uri(url)
                    .header("accept", "application/json")
                    .header("Authorization", authHeader)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        throw new InvalidTransitionException(String.format(
                                "Assignments API returned %s for activity %s",
                                resp.getStatusCode(), activityId));
                    })
                    .body(AssignmentResponse.class);

            if (body == null || body.getData() == null) {
                throw new InvalidTransitionException(
                        "Assignments API returned empty body for activity " + activityId);
            }
            return body.getData();

        } catch (InvalidTransitionException rethrow) {
            throw rethrow;
        } catch (Exception ex) {
            log.error("Assignments API call failed for activity {}: {}", activityId, ex.getMessage());
            throw new InvalidTransitionException(
                    "Could not fetch assignments for activity " + activityId + ": " + ex.getMessage());
        }
    }

    /* ============================================================ */

    private String currentAuthHeader() {
        return Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(ServletRequestAttributes.class::cast)
                .map(attrs -> attrs.getRequest().getHeader("Authorization"))
                .orElse(null);
    }
}
