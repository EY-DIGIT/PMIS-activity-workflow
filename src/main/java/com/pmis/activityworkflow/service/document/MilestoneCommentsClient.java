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
 * {@code POST /projects/api/v3/milestones/{milestoneId}/comments}.
 *
 * <p>Single responsibility:</p>
 * <ol>
 *   <li>Resolve {@code milestoneId} from an {@code activityId} via the
 *       upstream activity-lookup endpoint.</li>
 *   <li>POST a multipart request with the comment body + file.</li>
 *   <li>Parse the response and return commentId / milestoneId / fileUrl.</li>
 * </ol>
 *
 * <p>The caller's {@code Authorization} header is forwarded verbatim to
 * both upstream calls.</p>
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
        validateFile(file);

        String auth = currentAuthHeader();
        if (!StringUtils.hasText(auth)) {
            throw new InvalidTransitionException(
                    "Authorization header is required to call the comments API");
        }

        // ---- 1. activityId → milestoneId ----
        String milestoneId = resolveMilestoneId(activityId, auth);
        log.info("Resolved milestoneId={} for activityId={}", milestoneId, activityId);

        // ---- 2. POST comment + file ----
        String url = props.getCommentsUrlTemplate().replace("{milestoneId}", milestoneId);
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add(props.getBodyField(), commentBody == null ? "" : commentBody);
            body.add(props.getFilesField(), toFilePart(file));

            String raw = milestoneCommentsRestClient.post()
                    .uri(url)
                    .header("Authorization", auth)
                    .header("accept", "application/json")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        // Surface the upstream error message to the caller
                        // so they see e.g. "storage_unavailable: Permission denied".
                        String responseBody = new String(resp.getBody().readAllBytes());
                        log.error("Upstream comments API {} returned {}: {}",
                                url, resp.getStatusCode(), responseBody);
                        throw new InvalidTransitionException(
                                "Upstream comments API failed (" + resp.getStatusCode() + "): "
                                        + extractError(responseBody));
                    })
                    .body(String.class);

            log.debug("Upstream comments response: {}", raw);
            return parseResponse(raw, milestoneId, commentBody);

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

    /** Resolve activityId -> milestoneId via the upstream lookup. */
    private String resolveMilestoneId(String activityId, String auth) {
        if (!StringUtils.hasText(activityId)) {
            throw new InvalidTransitionException("activityId is required");
        }
        String url = props.getActivityLookupUrlTemplate().replace("{activityId}", activityId);
        try {
            String raw = milestoneCommentsRestClient.get()
                    .uri(url)
                    .header("Authorization", auth)
                    .header("accept", "application/json")
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(raw);

            // Try the common shapes — adjust here if upstream uses a different path.
            String id = firstNonNullText(root,
                    "milestoneId",
                    "data.milestoneId",
                    "data.milestone.id",
                    "data.milestone_id");
            if (!StringUtils.hasText(id)) {
                throw new IllegalStateException(
                        "Activity lookup did not contain milestoneId. Body: " + raw);
            }
            return id;

        } catch (Exception ex) {
            log.error("milestoneId lookup failed for activity {}: {}", activityId, ex.getMessage());
            throw new InvalidTransitionException(
                    "Could not resolve milestoneId from activityId " + activityId
                            + ": " + ex.getMessage());
        }
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

    /** Pull commentId / fileUrl out of the upstream response. */
    private MilestoneCommentResult parseResponse(String raw,
                                                 String milestoneId,
                                                 String commentBody) throws Exception {
        JsonNode root = objectMapper.readTree(raw);

        // Try standard wrappers - { "data": {...} } or flat
        JsonNode data = root.has("data") && !root.get("data").isNull() ? root.get("data") : root;

        String commentId = firstNonNullText(data,
                "id", "commentId", "comment_id");
        String fileUrl = firstNonNullText(data,
                "attachmentUrl", "fileUrl", "file_url",
                "attachments[0].url", "files[0].url");

        if (!StringUtils.hasText(commentId)) {
            log.warn("Upstream response had no commentId; raw response: {}", raw);
        }

        return MilestoneCommentResult.builder()
                .commentId(commentId)
                .milestoneId(milestoneId)
                .fileUrl(fileUrl)
                .commentBody(commentBody)
                .build();
    }

    /**
     * Walk dot-paths and array-indices like "data.milestone.id" or
     * "attachments[0].url". Returns the first one with a non-blank value.
     */
    private String firstNonNullText(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = walk(root, path);
            if (node != null && !node.isNull() && StringUtils.hasText(node.asText())) {
                return node.asText();
            }
        }
        return null;
    }

    private JsonNode walk(JsonNode root, String path) {
        JsonNode cur = root;
        for (String segment : path.split("\\.")) {
            if (cur == null) return null;
            if (segment.matches(".*\\[\\d+]$")) {
                int bracket = segment.lastIndexOf('[');
                String field = segment.substring(0, bracket);
                int idx = Integer.parseInt(segment.substring(bracket + 1, segment.length() - 1));
                cur = cur.path(field);
                if (!cur.isArray() || idx >= cur.size()) return null;
                cur = cur.get(idx);
            } else {
                cur = cur.path(segment);
            }
        }
        return cur;
    }

    /** Lift the {@code error.message} field out of the upstream error envelope. */
    private String extractError(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String msg = firstNonNullText(root, "error.message", "message", "error");
            return StringUtils.hasText(msg) ? msg : responseBody;
        } catch (Exception ignore) {
            return responseBody;
        }
    }
}
