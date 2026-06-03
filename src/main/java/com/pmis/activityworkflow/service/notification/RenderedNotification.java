package com.pmis.activityworkflow.service.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Output of the template engine — both fields are plain text, ready to
 * drop into an email or a push payload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenderedNotification {

    private String subject;
    private String body;
}
