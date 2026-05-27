package com.activityworkflow.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * A single state inside an Activity. Maps to aw_state.
 *
 * uuid is the only identifier. The legacy "seq" column from Digit has been
 * removed — Hibernate 6 doesn't allow @GeneratedValue on non-@Id fields and
 * nothing in this project needs a separate ordering column.
 */
@Entity
@Table(name = "aw_state")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class StateEntity {

    @Id
    @Column(name = "uuid", nullable = false, length = 64)
    @EqualsAndHashCode.Include
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", nullable = false)
    @ToString.Exclude
    private ActivityEntity activity;

    @Column(name = "state_name", length = 256)
    private String stateName;

    @Column(name = "application_status", length = 256)
    private String applicationStatus;

    @Column(name = "sla")
    private Long sla;

    @Column(name = "doc_upload_required")
    private Boolean docUploadRequired;

    @Column(name = "is_start_state")
    private Boolean isStartState;

    @Column(name = "is_terminate_state")
    private Boolean isTerminateState;

    @Column(name = "is_state_updatable")
    private Boolean isStateUpdatable;

    @OneToMany(
            mappedBy = "currentState",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    private List<ActionEntity> actions = new ArrayList<>();

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "createdBy",        column = @Column(name = "created_by")),
            @AttributeOverride(name = "createdTime",      column = @Column(name = "created_time")),
            @AttributeOverride(name = "lastModifiedBy",   column = @Column(name = "last_modified_by")),
            @AttributeOverride(name = "lastModifiedTime", column = @Column(name = "last_modified_time"))
    })
    private AuditDetails auditDetails;

    public void addAction(ActionEntity action) {
        action.setCurrentState(this);
        actions.add(action);
    }
}