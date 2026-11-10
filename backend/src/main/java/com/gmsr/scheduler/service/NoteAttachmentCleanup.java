package com.gmsr.scheduler.service;

import com.gmsr.scheduler.repository.NoteAttachmentRepository;
import java.nio.file.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NoteAttachmentCleanup {
 private final NoteAttachmentRepository attachments;
 private final Path root;
 public NoteAttachmentCleanup(NoteAttachmentRepository attachments,
   @Value("${gmsr.notes.directory:./data/note-attachments}") String directory) {
  this.attachments=attachments; this.root=Path.of(directory).toAbsolutePath().normalize();
 }
 @Scheduled(fixedDelay=3600000, initialDelay=3600000)
 public void cleanup() {
  Instant cutoff=Instant.now().minus(Duration.ofHours(24));
  for(var attachment:attachments.findByNoteIdIsNullAndCreatedAtBefore(cutoff)) {
   // Claim only still-unpublished records; a concurrent publish must keep its file.
   if(attachments.deleteExpiredStaged(attachment.id,cutoff)==0) continue;
   try { Files.deleteIfExists(root.resolve(attachment.storageKey)); }
   catch(Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("过期待发布附件的磁盘文件清理失败，需要检查存储目录"); }
  }
 }
}
