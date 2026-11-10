package com.gmsr.scheduler.service;

import com.gmsr.scheduler.repository.NoteAttachmentRepository;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class NoteAttachmentCleanup {
 private final NoteAttachmentRepository attachments;
 private final Path root;
 private final TransactionTemplate transaction;
 public NoteAttachmentCleanup(NoteAttachmentRepository attachments, PlatformTransactionManager manager,
   @Value("${gmsr.notes.directory:./data/note-attachments}") String directory) {
  this.attachments=attachments; this.root=Path.of(directory).toAbsolutePath().normalize(); this.transaction=new TransactionTemplate(manager);
 }
 @Scheduled(fixedDelay=900000, initialDelay=900000)
 public void cleanup() {
  Instant cutoff=Instant.now().minus(Duration.ofHours(24));
  for(var candidate:attachments.findTop100ByNoteIdIsNullAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(cutoff)) {
   try {
    transaction.executeWithoutResult(status -> {
     var attachment=attachments.findForPublication(candidate.id).orElse(null);
     if(attachment==null || attachment.noteId!=null || !attachment.createdAt.isBefore(cutoff)) return;
     // Hold the same row lock as publication. Disk failure keeps metadata for retry.
     try { Files.deleteIfExists(root.resolve(attachment.storageKey)); }
     catch(java.io.IOException ex) { throw new UncheckedIOException(ex); }
     attachments.delete(attachment);
    });
   } catch(Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("过期便签附件清理失败，保留记录稍后重试"); }
  }
 }
}
