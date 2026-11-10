package com.gmsr.scheduler.repository;
import com.gmsr.scheduler.domain.NoteAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
public interface NoteAttachmentRepository extends JpaRepository<NoteAttachment,String> {
 java.util.List<NoteAttachment> findByNoteIdOrderById(Long noteId);
 @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @org.springframework.data.jpa.repository.Query("select a from NoteAttachment a where a.id = :id")
 java.util.Optional<NoteAttachment> findForPublication(@org.springframework.data.repository.query.Param("id") String id);
 long countByNoteIdIsNullAndUploadedBy(Long uploadedBy);
 java.util.List<NoteAttachment> findByNoteIdIsNullAndCreatedAtBefore(java.time.Instant cutoff);
 @org.springframework.data.jpa.repository.Modifying
 @org.springframework.transaction.annotation.Transactional
 @org.springframework.data.jpa.repository.Query("delete from NoteAttachment a where a.id = :id and a.noteId is null and a.createdAt < :cutoff")
 int deleteExpiredStaged(@org.springframework.data.repository.query.Param("id") String id,
     @org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);
}
