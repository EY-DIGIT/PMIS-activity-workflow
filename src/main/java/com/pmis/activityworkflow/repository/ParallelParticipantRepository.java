package com.pmis.activityworkflow.repository;

import com.pmis.activityworkflow.entity.ParallelParticipantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParallelParticipantRepository
        extends JpaRepository<ParallelParticipantEntity, String> {

    /** All participants for one record at one state. */
    List<ParallelParticipantEntity>
        findByBusinessServiceAndActivityIdAndStateName(String businessService,
                                                      String activityId,
                                                      String stateName);

    /** Lookup one participant by user (for vote handling). */
    Optional<ParallelParticipantEntity>
        findByBusinessServiceAndActivityIdAndStateNameAndApproverUserUuid(
                String businessService, String activityId, String stateName, String userUuid);

    /** Count by voteStatus — used by the gate-evaluator to decide advance/reject/wait. */
    @Query("""
           SELECT p.voteStatus, COUNT(p) FROM ParallelParticipantEntity p
            WHERE p.businessService = :bs
              AND p.activityId      = :bid
              AND p.stateName       = :state
            GROUP BY p.voteStatus
           """)
    List<Object[]> countByVoteStatus(@Param("bs") String businessService,
                                     @Param("bid") String activityId,
                                     @Param("state") String stateName);

    /**
     * All participant rows where the given user is an approver, optionally
     * filtered to one vote status (e.g. only "PENDING"). Newest first.
     *
     * <p>Scoped to {@code state_name = 'PENDINGATCONCERNEDDIVISION'} — the
     * inbox is the gate-stage list. OWNER rows (state =
     * PENDINGATOWNERDIVISION, division_code = 'OWNER') are intentionally
     * excluded so a user who happens to be BOTH a division approver and
     * an owner approver doesn't see the same activity twice.</p>
     */
    /**
     * All participant rows where the given user is an approver, optionally
     * filtered by vote status and/or state. Newest first.
     *
     * <p>Pass {@code stateName=null} to return rows from every state
     * (gate + owner). Typical usage from the inbox controller is to pass
     * {@code "PENDINGATCONCERNEDDIVISION"} so a user who is both a
     * division approver and an owner approver doesn't see the same
     * activity twice.</p>
     */
    @Query("""
           SELECT p FROM ParallelParticipantEntity p
            WHERE p.approverUserUuid = :userUuid
              AND (:stateName  IS NULL OR p.stateName  = :stateName)
              AND (:voteStatus IS NULL OR p.voteStatus = :voteStatus)
            ORDER BY p.createdAt DESC
           """)
    List<ParallelParticipantEntity> findInboxForApprover(
            @Param("userUuid")   String userUuid,
            @Param("stateName")  String stateName,
            @Param("voteStatus") String voteStatus);

    /**
     * All participant rows for an activity, newest first. Used by the
     * approval detail screen to figure out (a) which state the activity is
     * currently parked at and (b) the full per-division status list.
     *
     * <p>activityId alone is unique enough for the lookup — an activity
     * cannot belong to two business services in this system.</p>
     */
    @Query("""
           SELECT p FROM ParallelParticipantEntity p
            WHERE p.activityId = :activityId
            ORDER BY p.createdAt DESC
           """)
    List<ParallelParticipantEntity> findInboxParticipantsForActivity(
            @Param("activityId") String activityId);
}