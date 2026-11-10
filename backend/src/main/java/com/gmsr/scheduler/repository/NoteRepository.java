package com.gmsr.scheduler.repository;
import com.gmsr.scheduler.domain.Note;
import org.springframework.data.jpa.repository.JpaRepository;
public interface NoteRepository extends JpaRepository<Note,Long> {
 java.util.List<Note> findAllByOrderByPinnedDescUpdatedAtDescIdDesc();
}
