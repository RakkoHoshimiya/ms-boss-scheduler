package com.gmsr.scheduler.repository;
import com.gmsr.scheduler.domain.NoteAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
public interface NoteAttachmentRepository extends JpaRepository<NoteAttachment,String> {
 java.util.List<NoteAttachment> findByNoteIdOrderById(Long noteId);
 @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @org.springframework.data.jpa.repository.Query("select a from NoteAttachment a where a.id = :id")
 java.util.Optional<NoteAttachment> findForPublication(@org.springframework.data.repository.query.Param("id") String id);
 long countByNoteIdIsNullAndRetiredFalseAndUploadedByAndCreatedAtAfter(Long uploadedBy, java.time.Instant cutoff);
 java.util.List<NoteAttachment> findTop100ByNoteIdIsNullAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(java.time.Instant cutoff);
}
