package com.pmis.activityworkflow.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.pmis.activityworkflow.entity.ParallelParticipantEntity;

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
     */
    @Query("""
           SELECT p FROM ParallelParticipantEntity p
            WHERE p.approverUserUuid = :userUuid
              AND (:voteStatus IS NULL OR p.voteStatus = :voteStatus)
            ORDER BY p.createdAt DESC
           """)
    List<ParallelParticipantEntity> findInboxForApprover(
            @Param("userUuid")   String userUuid,
            @Param("voteStatus") String voteStatus);
    
    /**
     * All participant rows for an activity, newest first. Used by the
     * approval detail screen to figure out (a) which state the activity is
     * currently parked at and (b) the full per-division status list.
     */
    @Query("""
           SELECT p FROM ParallelParticipantEntity p
            WHERE p.businessService = :businessService
              AND p.activityId      = :activityId
            ORDER BY p.createdAt DESC
           """)
    List<ParallelParticipantEntity> findInboxParticipantsForActivity(
            @Param("businessService") String businessService,
            @Param("activityId")      String activityId);
}
