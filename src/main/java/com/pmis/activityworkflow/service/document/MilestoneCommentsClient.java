package com.pmis.activityworkflow.service.document;

import com.pmis.activityworkflow.config.MilestoneCommentsProperties;
import com.pmis.activityworkflow.exception.InvalidTransitionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
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
        validateFile(file, commentBody);

        String auth = currentAuthHeader();
        if (!StringUtils.hasText(auth)) {
            throw new InvalidTransitionException(
                    "Authorization header is required to call the comments API");
        }

        // POST comment + (optional) file directly using the activityId
        String url = props.getCommentsUrlTemplate().replace("{activityId}", activityId);
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            // 'body' carries the comment text (admin's modal note)
            body.add(props.getBodyField(),
                    commentBody == null ? "Document uploaded" : commentBody);
            // 'files' carries the attached file - omitted entirely for
            // comment-only posts so the upstream sees a clean multipart
            // without an empty 'files' part. We wrap in an HttpEntity so
            // the part carries the file's actual Content-Type (e.g.
            // application/vnd.ms-excel for .xls) instead of the
            // application/octet-stream default that Spring uses for raw
            // Resource parts. Some upstreams reject octet-stream for
            // common office/image formats.
            if (file != null && !file.isEmpty()) {
                HttpHeaders fileHeaders = new HttpHeaders();
                String detected = file.getContentType();
                MediaType mediaType;
                try {
                    mediaType = (detected != null && !detected.isBlank())
                            ? MediaType.parseMediaType(detected)
                            : MediaType.APPLICATION_OCTET_STREAM;
                } catch (Exception parseFail) {
                    log.warn("Could not parse client-supplied Content-Type '{}', "
                            + "falling back to application/octet-stream", detected);
                    mediaType = MediaType.APPLICATION_OCTET_STREAM;
                }
                fileHeaders.setContentType(mediaType);
                HttpEntity<Resource> filePart = new HttpEntity<>(toFilePart(file), fileHeaders);
                body.add(props.getFilesField(), filePart);

                log.info("Uploading file '{}' (size={}, contentType={}) to upstream comments for activity {}",
                        file.getOriginalFilename(), file.getSize(), mediaType, activityId);
            }

            // Read as byte[] — ByteArrayHttpMessageConverter handles */* so it
            // never conflicts with Jackson's application/json converter (which
            // would try to deserialize the JSON object as String and fail).
            byte[] rawBytes = milestoneCommentsRestClient.post()
                    .uri(url)
                    .header("Authorization", auth)
                    .header("accept", "application/json")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        String responseBody = new String(resp.getBody().readAllBytes(),
                                StandardCharsets.UTF_8);
                        log.error("Upstream comments API {} returned {}: {}",
                                url, resp.getStatusCode(), responseBody);
                        throw new InvalidTransitionException(
                                "Upstream comments API failed (" + resp.getStatusCode() + "): "
                                        + extractError(responseBody));
                    })
                    .body(byte[].class);

            String raw = rawBytes != null
                    ? new String(rawBytes, StandardCharsets.UTF_8) : "{}";
            log.debug("Upstream comments response: {}", raw);
            String originalName = (file != null && StringUtils.hasText(file.getOriginalFilename()))
                    ? file.getOriginalFilename() : null;
            return parseResponse(raw, activityId, originalName);

        } catch (InvalidTransitionException rethrow) {
            throw rethrow;
        } catch (Exception ex) {
            log.error("Comment upload failed for activity {}: {}", activityId, ex.getMessage());
            throw new InvalidTransitionException(
                    "Comment upload failed: " + ex.getMessage());
        }
    }

    /**
     * Upload multiple files + a comment body in a single upstream request.
     * All files land as {@code attachments[]} on the same comment object.
     * Returns one {@link MilestoneCommentResult} whose {@code docId} is the
     * shared upstream comment id — use it as the {@code documentStoreId}
     * for all files in this batch.
     *
     * @param files       zero or more files; null / empty list is allowed when
     *                    {@code commentBody} is non-blank (comment-only post)
     * @param commentBody text body of the comment; defaults to "Document uploaded"
     *                    if blank and files are present
     */
    public MilestoneCommentResult uploadCommentWithFiles(String activityId,
                                                         List<MultipartFile> files,
                                                         String commentBody) {
        validateConfig();
        validateActivityId(activityId);

        List<MultipartFile> realFiles = files == null ? List.of() : files.stream()
                .filter(f -> f != null && !f.isEmpty())
                .toList();
        boolean hasComment = StringUtils.hasText(commentBody);
        if (realFiles.isEmpty() && !hasComment) {
            throw new InvalidTransitionException(
                    "Either one or more files or a non-blank comment is required");
        }
        for (MultipartFile f : realFiles) {
            if (f.getSize() > props.getMaxFileSizeBytes()) {
                throw new InvalidTransitionException(String.format(
                        "File '%s' exceeds max allowed size of %d bytes",
                        f.getOriginalFilename(), props.getMaxFileSizeBytes()));
            }
        }

        String auth = currentAuthHeader();
        if (!StringUtils.hasText(auth)) {
            throw new InvalidTransitionException(
                    "Authorization header is required to call the comments API");
        }

        String url = props.getCommentsUrlTemplate().replace("{activityId}", activityId);
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add(props.getBodyField(),
                    hasComment ? commentBody : "Document uploaded");

            for (MultipartFile file : realFiles) {
                HttpHeaders fileHeaders = new HttpHeaders();
                String detected = file.getContentType();
                MediaType mediaType;
                try {
                    mediaType = (detected != null && !detected.isBlank())
                            ? MediaType.parseMediaType(detected)
                            : MediaType.APPLICATION_OCTET_STREAM;
                } catch (Exception parseFail) {
                    mediaType = MediaType.APPLICATION_OCTET_STREAM;
                }
                fileHeaders.setContentType(mediaType);
                body.add(props.getFilesField(),
                        new HttpEntity<>(toFilePart(file), fileHeaders));
                log.info("Queuing file '{}' (size={}) for batch upload to activity {}",
                        file.getOriginalFilename(), file.getSize(), activityId);
            }

            byte[] rawBytes = milestoneCommentsRestClient.post()
                    .uri(url)
                    .header("Authorization", auth)
                    .header("accept", "application/json")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        String responseBody = new String(resp.getBody().readAllBytes(),
                                StandardCharsets.UTF_8);
                        log.error("Upstream comments API {} returned {}: {}",
                                url, resp.getStatusCode(), responseBody);
                        throw new InvalidTransitionException(
                                "Upstream comments API failed (" + resp.getStatusCode() + "): "
                                        + extractError(responseBody));
                    })
                    .body(byte[].class);

            String raw = rawBytes != null
                    ? new String(rawBytes, StandardCharsets.UTF_8) : "{}";
            log.debug("Upstream batch-upload response: {}", raw);
            String firstName = realFiles.isEmpty() ? null
                    : realFiles.get(0).getOriginalFilename();
            return parseResponse(raw, activityId, firstName);

        } catch (InvalidTransitionException rethrow) {
            throw rethrow;
        } catch (Exception ex) {
            log.error("Batch comment upload failed for activity {}: {}", activityId, ex.getMessage());
            throw new InvalidTransitionException(
                    "Batch comment upload failed: " + ex.getMessage());
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

    private void validateFile(MultipartFile file, String commentBody) {
        // Comment-only posts are allowed - file is optional - but we must
        // have SOMETHING to send, otherwise the upstream call is a no-op
        // that returns 422.
        boolean hasFile    = file != null && !file.isEmpty();
        boolean hasComment = StringUtils.hasText(commentBody);
        if (!hasFile && !hasComment) {
            throw new InvalidTransitionException(
                    "Either a file or a non-blank comment is required");
        }
        if (hasFile && file.getSize() > props.getMaxFileSizeBytes()) {
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
    private MilestoneCommentResult parseResponse(String raw, String activityId,
                                                  String originalFileName) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode data = root.has("data") && !root.get("data").isNull()
                ? root.get("data") : root;

        String docId = data.path("id").asText(null);
        if (!StringUtils.hasText(docId)) {
            throw new IllegalStateException(
                    "Upstream comments API returned no 'id'. Raw: " + raw);
        }

        String returnedTargetId = data.path("targetId").asText(null);
        String resolvedActivityId = StringUtils.hasText(returnedTargetId)
                ? returnedTargetId
                : activityId;

        JsonNode author = data.path("author");
        String authorEmail = author.path("email").asText(null);
        String authorLogin = author.path("login").asText(null);

        // Extract ALL attachments from the upstream response.
        List<MilestoneCommentResult.Attachment> attachments = extractAllAttachments(data);

        // Convenience first-file fields.
        String firstFileName = attachments.isEmpty() ? originalFileName
                : attachments.get(0).getFileName();
        String firstFileUrl = attachments.isEmpty() ? null
                : attachments.get(0).getFileUrl();

        return MilestoneCommentResult.builder()
                .docId(docId)
                .activityId(resolvedActivityId)
                .authorEmail(authorEmail)
                .authorLogin(authorLogin)
                .fileName(firstFileName)
                .fileUrl(firstFileUrl)
                .attachments(attachments)
                .build();
    }

    /**
     * Extract all file attachments from the upstream POST response.
     * Tries {@code data.attachments[]}, {@code data.files[]}, and
     * {@code data.file} (single object), in that priority order.
     */
    private List<MilestoneCommentResult.Attachment> extractAllAttachments(JsonNode data) {
        List<MilestoneCommentResult.Attachment> result = new ArrayList<>();

        // Primary shape: data.attachments[]
        JsonNode attachments = data.path("attachments");
        if (attachments.isArray() && !attachments.isEmpty()) {
            for (JsonNode a : attachments) {
                String url = a.path("url").asText(null);
                if (!StringUtils.hasText(url)) continue;
                result.add(MilestoneCommentResult.Attachment.builder()
                        .fileName(a.path("filename").asText(null))
                        .fileUrl(url)
                        .mimeType(a.path("mimeType").asText(null))
                        .sizeBytes(a.has("sizeBytes") && !a.get("sizeBytes").isNull()
                                ? a.get("sizeBytes").asLong() : null)
                        .build());
            }
            if (!result.isEmpty()) return result;
        }

        // Fallback: data.files[]
        JsonNode files = data.path("files");
        if (files.isArray() && !files.isEmpty()) {
            for (JsonNode f : files) {
                String url = f.path("url").asText(null);
                if (!StringUtils.hasText(url)) continue;
                result.add(MilestoneCommentResult.Attachment.builder()
                        .fileName(f.path("filename").asText(
                                f.path("name").asText(null)))
                        .fileUrl(url)
                        .mimeType(f.path("mimeType").asText(null))
                        .build());
            }
            if (!result.isEmpty()) return result;
        }

        // Last resort: data.file (single object)
        JsonNode file = data.path("file");
        if (file.isObject()) {
            String url = file.path("url").asText(null);
            if (StringUtils.hasText(url)) {
                result.add(MilestoneCommentResult.Attachment.builder()
                        .fileName(file.path("filename").asText(null))
                        .fileUrl(url)
                        .build());
            }
        }
        return result;
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