package com.activityworkflow.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Root workflow definition. Maps to aw_activity.
 *
 * Owns a list of {@link StateEntity} via @OneToMany cascade — saving an
 * Activity also persists its states (and their actions through the next
 * cascade level), all in one transaction.
 */
@Entity
@Table(name = "aw_activity")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ActivityEntity {

    @Id
    @Column(name = "uuid", nullable = false, length = 64)
    @EqualsAndHashCode.Include
    private String uuid;

    @Column(name = "activity_name", nullable = false, length = 256)
    private String activityName;

    @Column(name = "business_module", length = 256)
    private String businessModule;

    @Column(name = "activity_sla")
    private Long activitySla;

    @Column(name = "get_uri", length = 512)
    private String getUri;

    @Column(name = "post_uri", length = 512)
    private String postUri;

    @OneToMany(
            mappedBy = "activity",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    private List<StateEntity> states = new ArrayList<>();

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "createdBy",        column = @Column(name = "created_by")),
            @AttributeOverride(name = "createdTime",      column = @Column(name = "created_time")),
            @AttributeOverride(name = "lastModifiedBy",   column = @Column(name = "last_modified_by")),
            @AttributeOverride(name = "lastModifiedTime", column = @Column(name = "last_modified_time"))
    })
    private AuditDetails auditDetails;

    /** Keep bidirectional FK consistent — call this, not setStates(...). */
    public void addState(StateEntity state) {
        state.setActivity(this);
        states.add(state);
    }
}
