package com.pmis.activityworkflow.service.assignments;

import com.pmis.activityworkflow.config.MilestoneCommentsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Fetches activity + project details from the upstream system for use
 * by the approval inbox.
 *
 * <p>Both calls forward the caller's Authorization header verbatim.
 * Failures here are tolerated — we log and return null so the inbox
 * row still renders with whatever data we have, instead of failing
 * the whole list.</p>
 */
@Service
@Slf4j
public class ActivityDetailsClient {

    private final MilestoneCommentsProperties props;
    private final RestClient milestoneCommentsRestClient;
    private final ObjectMapper objectMapper;

    public ActivityDetailsClient(
            MilestoneCommentsProperties props,
            @Qualifier("milestoneCommentsRestClient") RestClient milestoneCommentsRestClient,
            ObjectMapper objectMapper) {
        this.props = props;
        this.milestoneCommentsRestClient = milestoneCommentsRestClient;
        this.objectMapper = objectMapper;
    }

    /** Get full activity JSON (the {@code data} block). */
    public JsonNode fetchActivity(String activityId) {
        if (!StringUtils.hasText(activityId)) return null;
        String url = props.getActivityLookupUrlTemplate().replace("{activityId}", activityId);
        return fetchData(url, "activity " + activityId);
    }

    /**
     * Get full project JSON (the {@code data} block). The project URL is
     * derived from the activity URL template — same base, different path.
     */
    public JsonNode fetchProject(String projectId) {
        if (!StringUtils.hasText(projectId)) return null;
        String url = projectLookupUrl(projectId);
        return fetchData(url, "project " + projectId);
    }

    /**
     * GET the comments + attachments collection for an activity.
     *
     * <p>Returns the {@code _embedded.elements} array (i.e. each individual
     * comment object). If the upstream call fails or returns no data,
     * returns null — callers should tolerate that.</p>
     */
    public JsonNode fetchActivityComments(String activityId) {
        if (!StringUtils.hasText(activityId)) return null;
        String url = props.getActivityLookupUrlTemplate()
                .replace("{activityId}", activityId) + "/comments";
        JsonNode data = fetchData(url, "comments for activity " + activityId);
        if (data == null) return null;
        // Response shape: { data: { _embedded: { elements: [...] } } }
        JsonNode elements = data.path("_embedded").path("elements");
        return elements.isArray() ? elements : null;
    }

    /* ============================================================ */

    private String projectLookupUrl(String projectId) {
        // Reuse the same host as the activity-lookup URL by substituting
        // /activities/ -> /projects/ and {activityId} -> projectId.
        String base = props.getActivityLookupUrlTemplate()
                .replace("/activities/{activityId}", "/projects/" + projectId);
        // Defensive — if the template doesn't match the expected shape,
        // just append /projects/{id}.
        if (!base.contains("/projects/")) {
            int apiIdx = base.indexOf("/api/");
            if (apiIdx > 0) {
                String prefix = base.substring(0, apiIdx);
                base = prefix + "/api/v3/projects/" + projectId;
            }
        }
        return base;
    }

    private JsonNode fetchData(String url, String contextForLogs) {
        String auth = currentAuthHeader();
        if (!StringUtils.hasText(auth)) {
            log.warn("No Authorization header on the inbound request - cannot fetch {}", contextForLogs);
            return null;
        }
        try {
            String raw = milestoneCommentsRestClient.get()
                    .uri(url)
                    .header("Authorization", auth)
                    .header("accept", "application/json")
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(raw);
            return root.has("data") && !root.get("data").isNull() ? root.get("data") : root;
        } catch (Exception ex) {
            log.warn("Upstream fetch for {} failed: {}", contextForLogs, ex.getMessage());
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
}
