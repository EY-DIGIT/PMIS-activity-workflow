package com.pmis.activityworkflow.service.notification;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads plain-text templates from {@code classpath:notifications/{EVENT}.txt}
 * once at startup, then renders them on demand with simple {@code {{var}}}
 * substitution.
 *
 * <p>Each file has the format:</p>
 * <pre>
 *   ===SUBJECT===
 *   {{...}} subject line {{...}}
 *
 *   ===BODY===
 *   {{...}} body text {{...}}
 * </pre>
 *
 * <p>No external template-engine dependency — keeps the project lean and
 * the templates trivial to audit. If a placeholder has no matching variable,
 * it is replaced with an empty string and a warning is logged.</p>
 */
@Service
@Slf4j
public class NotificationTemplateService {

    /** Matches {{ varName }} (whitespace tolerated). */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private static final Pattern SECTION_SPLIT =
            Pattern.compile("(?m)^===\\s*(SUBJECT|BODY)\\s*===\\s*$");

    private static final DateTimeFormatter HUMAN_TIME =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
                    .withZone(ZoneId.systemDefault());

    private final Map<NotificationEvent, Template> cache = new EnumMap<>(NotificationEvent.class);

    /* ============================================================
     *  Startup — load every template once.
     * ============================================================ */
    @PostConstruct
    void loadTemplates() {
        for (NotificationEvent event : NotificationEvent.values()) {
            try {
                cache.put(event, load(event));
                log.debug("Loaded notification template: {}", event);
            } catch (IOException e) {
                log.warn("Could not load template for {} - notifications of this kind will fall back to a default: {}",
                        event, e.getMessage());
            }
        }
        log.info("Notification templates loaded ({} of {})",
                cache.size(), NotificationEvent.values().length);
    }

    private Template load(NotificationEvent event) throws IOException {
        String path = "notifications/" + event.name() + ".txt";
        ClassPathResource resource = new ClassPathResource(path);
        try (var in = resource.getInputStream()) {
            String raw = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            return parse(raw);
        }
    }

    /** Split the file on the ===SECTION=== markers. */
    private Template parse(String raw) {
        Matcher m = SECTION_SPLIT.matcher(raw);
        String subject = "";
        String body = "";

        // collect section -> text by walking the matches
        int lastEnd = 0;
        String currentSection = null;
        while (m.find()) {
            String prevContent = raw.substring(lastEnd, m.start()).trim();
            if (currentSection != null) {
                if ("SUBJECT".equals(currentSection))      subject = prevContent;
                else if ("BODY".equals(currentSection))    body    = prevContent;
            }
            currentSection = m.group(1);
            lastEnd = m.end();
        }
        // tail after the last marker
        if (currentSection != null) {
            String tail = raw.substring(lastEnd).trim();
            if ("SUBJECT".equals(currentSection))          subject = tail;
            else if ("BODY".equals(currentSection))        body    = tail;
        }
        return new Template(subject, body);
    }

    /* ============================================================
     *  Render
     * ============================================================ */

    public RenderedNotification render(NotificationEvent event, Map<String, Object> variables) {
        Template tpl = cache.get(event);
        if (tpl == null) {
            // graceful fallback so a missing template never blocks a workflow
            return RenderedNotification.builder()
                    .subject("Activity Workflow notification: " + event.name())
                    .body("Event: " + event.name()
                            + "\nVariables: " + variables)
                    .build();
        }
        return RenderedNotification.builder()
                .subject(substitute(tpl.subject(), variables))
                .body(substitute(tpl.body(), variables))
                .build();
    }

    private String substitute(String text, Map<String, Object> vars) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object value = vars == null ? null : vars.get(key);
            m.appendReplacement(out, Matcher.quoteReplacement(format(value)));
            if (value == null) {
                log.debug("Template placeholder '{}' had no value, substituted empty string", key);
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Default-format known types (timestamps as human-readable) without losing flexibility. */
    private String format(Object value) {
        if (value == null) return "";
        if (value instanceof Long ts && ts > 0 && ts < 4_102_444_800_000L) {
            // looks like an epoch-millis timestamp (before year 2100)
            return HUMAN_TIME.format(Instant.ofEpochMilli(ts));
        }
        return value.toString();
    }

    /** Internal record. */
    private record Template(String subject, String body) {}
}
