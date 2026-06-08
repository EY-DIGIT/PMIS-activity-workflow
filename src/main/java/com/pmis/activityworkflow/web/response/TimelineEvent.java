package com.pmis.activityworkflow.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the activity timeline. Designed to render as a single
 * "card" or "row" in a feed UI — UI keys off {@link #kind} to pick
 * the icon and color treatment.
 *
 * <p>{@code title} is a short human-readable summary the UI can show
 * verbatim. {@code detail} carries optional additional context (e.g.
 * the user's comment). Everything else is metadata the UI can use for
 * subtitles, tooltips, links.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TimelineEvent {

    /** One of: STATE_TRANSITION, VOTE, OWNER_ACTION. */
    private String kind;

    /** Short summary: "Activity submitted", "TMD1 approved", etc. */
    private String title;

    /** Optional - user's comment or extra context. */
    private String detail;

    /** ms epoch, used for ordering and display. */
    private Long timestamp;

    /* ----- actor ----- */
    private String actorUuid;
    private String actorUsername;

    /* ----- state context (only meaningful for STATE_TRANSITION) ----- */
    private String previousState;
    private String resultantState;

    /* ----- workflow context ----- */
    private String actionName;
    private String activityId;
    private String projectId;
    private String businessService;

    /** Stable id from aw_workflow_audit — useful as a React key. */
    private String eventId;
}
