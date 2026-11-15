package com.gmsr.scheduler.repository;
import com.gmsr.scheduler.domain.NoteAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
public interface NoteAttachmentRepository extends JpaRepository<NoteAttachment,String> {
 java.util.List<NoteAttachment> findByNoteIdOrderById(Long noteId);
 @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @org.springframework.data.jpa.repository.Query("select a from NoteAttachment a where a.id = :id")
 java.util.Optional<NoteAttachment> findForPublication(@org.springframework.data.repository.query.Param("id") String id);
 long countByNoteIdIsNullAndRetiredFalseAndUploadedByAndCreatedAtAfter(Long uploadedBy, java.time.Instant cutoff);
 @org.springframework.data.jpa.repository.Query("select a from NoteAttachment a where a.noteId is null and a.createdAt <= :cutoff and not exists (select d.id from NoteDraft d join d.attachments f where f.id=a.id) order by a.createdAt,a.id")
 java.util.List<NoteAttachment> findCleanupCandidates(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff,org.springframework.data.domain.Pageable page);
}
