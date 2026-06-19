package com.pmis.activityworkflow.scheduler;

import com.pmis.activityworkflow.config.ReminderProperties;
import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import com.pmis.activityworkflow.repository.ParallelParticipantRepository;
import com.pmis.activityworkflow.service.notification.NotificationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Cron job — sends a daily reminder email to every approver whose item is
 * STILL PENDING and was created within the last
 * {@code app.reminder.window-days} days (default 30).
 *
 * <p>Reminder cadence:
 * <ul>
 *   <li>Day 1 (request day) — first reminder sent that evening (09:00 cron).</li>
 *   <li>Day 2 to Day 30    — reminder sent every day.</li>
 *   <li>Day 31+            — item falls outside the window; no more reminders.</li>
 * </ul>
 *
 * <p>Covers both stages:
 * <ul>
 *   <li><b>Concerned division</b> — {@code PENDINGATCONCERNEDDIVISION}</li>
 *   <li><b>Owner division</b>    — {@code PENDINGATOWNERDIVISION}</li>
 * </ul>
 *
 * <p>Schedule (configured in {@code application.properties}):
 * <pre>app.reminder.cron = 0 0 9 * * *   # 09:00 every day</pre>
 *
 * <p>Disable entirely:
 * <pre>app.reminder.enabled = false</pre>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PendingApprovalReminderJob {

    private final ReminderProperties props;
    private final ParallelParticipantRepository participantRepository;
    private final NotificationClient notificationClient;

    @Scheduled(cron = "${app.reminder.cron:0 0 9 * * *}")
    public void sendReminders() {
        if (!props.isEnabled()) {
            log.debug("Pending-approval reminder job is disabled — skipping.");
            return;
        }

        long now = System.currentTimeMillis();

        // Only include items created within the last windowDays (e.g., 30 days).
        // Items older than windowDays fall outside the window and are skipped.
        long windowStart   = now - TimeUnit.DAYS.toMillis(props.getWindowDays());

        // Skip items that already received a reminder within the last resendDays (default 1 day).
        // This ensures at most one reminder per day per approver.
        long dailyThreshold = now - TimeUnit.DAYS.toMillis(props.getResendDays());

        List<ParallelParticipantEntity> due =
                participantRepository.findPendingForReminder(windowStart, dailyThreshold);

        if (due.isEmpty()) {
            log.info("Reminder job: no approvers due for a reminder today.");
            return;
        }

        log.info("Reminder job: {} approver(s) due for daily reminder (within {}-day window).",
                due.size(), props.getWindowDays());

        int sent   = 0;
        int failed = 0;

        for (ParallelParticipantEntity participant : due) {
            long pendingDays = TimeUnit.MILLISECONDS.toDays(now - participant.getCreatedAt());
            try {
                notificationClient.notifyReminder(participant, pendingDays);
                sent++;
            } catch (Exception ex) {
                // notifyReminder already logs individual failures; count them here.
                failed++;
                log.warn("Reminder job: unexpected error for approver {} on activity {}: {}",
                        participant.getApproverUserUuid(), participant.getActivityId(),
                        ex.getMessage());
            }
        }

        log.info("Reminder job complete: {} sent, {} failed.", sent, failed);
    }
}
