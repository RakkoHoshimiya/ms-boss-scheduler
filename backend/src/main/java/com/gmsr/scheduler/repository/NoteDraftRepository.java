package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.NoteDraft;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface NoteDraftRepository extends JpaRepository<NoteDraft,String> {
 List<NoteDraft> findByOwnerIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long ownerId);
 List<NoteDraft> findByOwnerIdAndCompletedFalseAndDeletedAtAfterOrderByDeletedAtDesc(Long ownerId,Instant cutoff);
 List<NoteDraft> findTop100ByDeletedAtBeforeOrderByDeletedAtAsc(Instant cutoff);
 long countByOwnerIdAndDeletedAtIsNull(Long ownerId);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from NoteDraft d where d.id=:id")
 Optional<NoteDraft> findForUpdate(@Param("id") String id);
 @Query("select count(d) > 0 from NoteDraft d join d.attachments a where a.id=:aid")
 boolean referencesAttachment(@Param("aid") String aid);
 @Query("select count(d) > 0 from NoteDraft d join d.attachments a where a.id=:aid and d.ownerId=:owner and d.deletedAt is null")
 boolean ownsActiveAttachment(@Param("aid") String aid,@Param("owner") Long owner);
}
