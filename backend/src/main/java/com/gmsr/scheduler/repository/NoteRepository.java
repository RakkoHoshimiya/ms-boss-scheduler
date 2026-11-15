package com.gmsr.scheduler.repository;
import com.gmsr.scheduler.domain.Note;
import org.springframework.data.jpa.repository.JpaRepository;
public interface NoteRepository extends JpaRepository<Note,Long> {
 java.util.List<Note> findByDeletedAtIsNullOrderByPinnedDescUpdatedAtDescIdDesc();
 java.util.List<Note> findByDeletedAtAfterOrderByDeletedAtDesc(java.time.Instant cutoff);
 java.util.List<Note> findTop100ByDeletedAtBeforeOrderByDeletedAtAsc(java.time.Instant cutoff);
 @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @org.springframework.data.jpa.repository.Query("select n from Note n where n.id = :id")
 java.util.Optional<Note> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
