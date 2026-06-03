package com.pmis.activityworkflow.service.document;

import com.pmis.activityworkflow.config.MilestoneCommentsProperties;
import com.pmis.activityworkflow.exception.InvalidTransitionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Wraps the upstream comments API at
 * {@code POST /projects/api/v3/activities/{activityId}/comments}.
 *
 * <p>Single responsibility:</p>
 * <ol>
 *   <li>POST a multipart request with the comment body + file directly to
 *       the activity (no milestoneId lookup needed — the upstream API now
 *       keys comments by activityId directly).</li>
 *   <li>Parse the response and return commentId / fileUrl.</li>
 * </ol>
 *
 * <p>The caller's {@code Authorization} header is forwarded verbatim.</p>
 */
@Service
@Slf4j
public class MilestoneCommentsClient {

    private final MilestoneCommentsProperties props;
    private final RestClient milestoneCommentsRestClient;
    private final ObjectMapper objectMapper;

    public MilestoneCommentsClient(
            MilestoneCommentsProperties props,
            @Qualifier("milestoneCommentsRestClient") RestClient milestoneCommentsRestClient,
            ObjectMapper objectMapper) {
        this.props = props;
        this.milestoneCommentsRestClient = milestoneCommentsRestClient;
        this.objectMapper = objectMapper;
    }

    public MilestoneCommentResult uploadComment(String activityId,
                                                MultipartFile file,
                                                String commentBody) {
        validateConfig();
        validateActivityId(activityId);
        validateFile(file);

        String auth = currentAuthHeader();
        if (!StringUtils.hasText(auth)) {
            throw new InvalidTransitionException(
                    "Authorization header is required to call the comments API");
        }

        // POST comment + file directly using the activityId
        String url = props.getCommentsUrlTemplate().replace("{activityId}", activityId);
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            // 'body' carries the comment text (admin's modal note)
            body.add(props.getBodyField(),
                    commentBody == null ? "Document uploaded" : commentBody);
            // 'files' carries the attached file
            body.add(props.getFilesField(), toFilePart(file));

            String raw = milestoneCommentsRestClient.post()
                    .uri(url)
                    .header("Authorization", auth)
                    .header("accept", "application/json")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        // Surface the upstream error message to the caller so
                        // operators see e.g. "storage_unavailable: Permission
                        // denied: /mnt/pmis_files/..." instead of just a 503.
                        String responseBody = new String(resp.getBody().readAllBytes());
                        log.error("Upstream comments API {} returned {}: {}",
                                url, resp.getStatusCode(), responseBody);
                        throw new InvalidTransitionException(
                                "Upstream comments API failed (" + resp.getStatusCode() + "): "
                                        + extractError(responseBody));
                    })
                    .body(String.class);

            log.debug("Upstream comments response: {}", raw);
            return parseResponse(raw, activityId);

        } catch (InvalidTransitionException rethrow) {
            throw rethrow;
        } catch (Exception ex) {
            log.error("Comment upload failed for activity {}: {}", activityId, ex.getMessage());
            throw new InvalidTransitionException(
                    "Comment upload failed: " + ex.getMessage());
        }
    }

    /* ============================================================ */

    private void validateConfig() {
        if (!props.isEnabled()) {
            throw new InvalidTransitionException(
                    "Comments API integration is disabled");
        }
        if (!StringUtils.hasText(props.getCommentsUrlTemplate())) {
            throw new InvalidTransitionException(
                    "app.milestone-comments.comments-url-template is not configured");
        }
    }

    private void validateActivityId(String activityId) {
        if (!StringUtils.hasText(activityId)) {
            throw new InvalidTransitionException("activityId is required");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidTransitionException("File is required and must not be empty");
        }
        if (file.getSize() > props.getMaxFileSizeBytes()) {
            throw new InvalidTransitionException(String.format(
                    "File '%s' exceeds max allowed size of %d bytes",
                    file.getOriginalFilename(), props.getMaxFileSizeBytes()));
        }
    }

    /** Forward whatever Authorization the inbound request carries. */
    private String currentAuthHeader() {
        return Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(ServletRequestAttributes.class::cast)
                .map(attrs -> attrs.getRequest().getHeader("Authorization"))
                .orElse(null);
    }

    /** Wrap multipart bytes as a Spring Resource so the filename survives. */
    private Resource toFilePart(MultipartFile file) throws IOException {
        String filename = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename()
                : "upload-" + UUID.randomUUID();
        return new ByteArrayResource(file.getBytes()) {
            @Override public String getFilename() { return filename; }
        };
    }

    /**
     * Parse the upstream response.
     *
     * <p>Expected shape:</p>
     * <pre>
     *   {
     *     "data": {
     *       "id":       "35d154e9-1a33-409d-9426-383a4c71aad3",
     *       "targetId": "ab07aec6-b636-4b7e-9383-80e8d647ea16",
     *       ...everything else we ignore...
     *     }
     *   }
     * </pre>
     */
    private MilestoneCommentResult parseResponse(String raw, String activityId) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode data = root.has("data") && !root.get("data").isNull()
                ? root.get("data") : root;

        String docId = data.path("id").asText(null);
        if (!StringUtils.hasText(docId)) {
            throw new IllegalStateException(
                    "Upstream comments API returned no 'id'. Raw: " + raw);
        }

        // Prefer targetId from the response (authoritative); fall back to the
        // activityId we sent in.
        String returnedTargetId = data.path("targetId").asText(null);
        String resolvedActivityId = StringUtils.hasText(returnedTargetId)
                ? returnedTargetId
                : activityId;

        // Best-effort author info — the upstream knows who its token belongs to.
        JsonNode author = data.path("author");
        String authorEmail = author.path("email").asText(null);
        String authorLogin = author.path("login").asText(null);

        return MilestoneCommentResult.builder()
                .docId(docId)
                .activityId(resolvedActivityId)
                .authorEmail(authorEmail)
                .authorLogin(authorLogin)
                .build();
    }

    /** Lift a human-readable message out of the upstream error envelope. */
    private String extractError(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            // Try error.message → message → error, in that order
            JsonNode errorNode = root.path("error");
            if (errorNode.isObject()) {
                String nested = errorNode.path("message").asText(null);
                if (StringUtils.hasText(nested)) return nested;
            }
            String top = root.path("message").asText(null);
            if (StringUtils.hasText(top)) return top;
            String err = errorNode.isTextual() ? errorNode.asText() : null;
            if (StringUtils.hasText(err)) return err;
            return responseBody;
        } catch (Exception ignore) {
            return responseBody;
        }
    }
}