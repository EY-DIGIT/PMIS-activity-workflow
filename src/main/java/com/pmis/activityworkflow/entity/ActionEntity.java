package com.pmis.activityworkflow.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * Edge in the state graph. Maps to aw_action.
 *
 * roles is stored as PostgreSQL TEXT[]; resolves via @JdbcTypeCode(ARRAY)
 * (Hibernate 6 feature). For non-Postgres DBs swap to a comma-string
 * converter.
 */
@Entity
@Table(name = "aw_action")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ActionEntity {

    @Id
    @Column(name = "uuid", nullable = false, length = 64)
    @EqualsAndHashCode.Include
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_state", nullable = false)
    @ToString.Exclude
    private StateEntity currentState;

    @Column(name = "active")
    private Boolean active;

    @Column(name = "action_name", length = 256)
    private String actionName;

    /** UUID of the next state. Kept loose (no hard FK) so authors can define transitions freely. */
    @Column(name = "next_state", length = 64)
    private String nextState;

    @Column(name = "roles", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> roles;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "createdBy",        column = @Column(name = "created_by")),
            @AttributeOverride(name = "createdTime",      column = @Column(name = "created_time")),
            @AttributeOverride(name = "lastModifiedBy",   column = @Column(name = "last_modified_by")),
            @AttributeOverride(name = "lastModifiedTime", column = @Column(name = "last_modified_time"))
    })
    private AuditDetails auditDetails;
}
