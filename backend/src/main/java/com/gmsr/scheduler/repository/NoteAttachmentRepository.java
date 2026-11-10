package com.gmsr.scheduler.repository;
import com.gmsr.scheduler.domain.NoteAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
public interface NoteAttachmentRepository extends JpaRepository<NoteAttachment,String> {
 java.util.List<NoteAttachment> findByNoteIdOrderById(Long noteId);
}
