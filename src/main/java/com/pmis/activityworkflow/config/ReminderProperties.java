package com.pmis.activityworkflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the pending-approval reminder cron job.
 *
 * <pre>
 * app.reminder.enabled          = true
 * app.reminder.cron             = 0 0 9 * * *   (daily 09:00)
 * app.reminder.window-days      = 30             (send reminders for the first N days only)
 * app.reminder.resend-days      = 1              (remind at most once per N days; 1 = daily)
 * </pre>
 *
 * <p>Behaviour: starting from the day approval is requested, a reminder is sent
 * every day until {@code window-days} have elapsed. After that the item falls
 * outside the window and no more reminders are sent regardless of vote status.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.reminder")
@Data
public class ReminderProperties {

    /** Master switch — set false to disable the cron entirely. */
    private boolean enabled = true;

    /**
     * Spring cron expression for when the job fires.
     * Default: 09:00 every day.
     */
    private String cron = "0 0 9 * * *";

    /**
     * The reminder window in days. Only items created within the last
     * {@code windowDays} days receive reminders.  Items older than this
     * threshold are silently skipped (the 30-day stop condition).
     * Default: 30.
     */
    private int windowDays = 30;

    /**
     * Minimum gap (in days) between two reminders for the same approver on
     * the same item. {@code 1} means at most one reminder per calendar day.
     * Default: 1.
     */
    private int resendDays = 1;
}
