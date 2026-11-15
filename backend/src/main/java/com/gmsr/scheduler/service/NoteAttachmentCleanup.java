package com.gmsr.scheduler.service;

import com.gmsr.scheduler.repository.*;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Purge expired recycle-bin content, then files no live note or draft references. */
@Component
public class NoteAttachmentCleanup {
 private final NoteAttachmentRepository attachments;
 private final NoteRepository notes;
 private final NoteDraftRepository drafts;
 private final Path root;
 private final TransactionTemplate transaction;
 private ScheduledExecutorService timer;
 public NoteAttachmentCleanup(NoteAttachmentRepository attachments, NoteRepository notes, NoteDraftRepository drafts,
   PlatformTransactionManager manager,@Value("${gmsr.notes.directory:./data/note-attachments}") String directory) {
  this.attachments=attachments; this.notes=notes; this.drafts=drafts;
  this.root=Path.of(directory).toAbsolutePath().normalize(); this.transaction=new TransactionTemplate(manager);
 }
 @PostConstruct void start() {
  timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"gmsr-note-cleanup");t.setDaemon(true);return t;});
  timer.scheduleWithFixedDelay(this::safeCleanup,60,900,TimeUnit.SECONDS);
 }
 @PreDestroy void stop() { if(timer!=null) timer.shutdownNow(); }
 private void safeCleanup() { try { cleanup(); } catch(Exception e) { warn(); } }
 private void warn() { org.slf4j.LoggerFactory.getLogger(getClass()).warn("过期攻略清理失败，保留记录稍后重试"); }
 public void cleanup() {
  Instant cutoff=Instant.now().minus(Duration.ofDays(7));
  for(var candidate:drafts.findTop100ByDeletedAtBeforeOrderByDeletedAtAsc(cutoff)) {
   try { transaction.executeWithoutResult(status->{
    var d=drafts.findForUpdate(candidate.id).orElse(null);
    if(d==null || d.deletedAt==null || !d.deletedAt.isBefore(cutoff)) return;
    d.attachments.clear(); drafts.delete(d); drafts.flush();
   }); } catch(Exception e) { warn(); }
  }
  for(var candidate:notes.findTop100ByDeletedAtBeforeOrderByDeletedAtAsc(cutoff)) {
   try { transaction.executeWithoutResult(status->{
    var n=notes.findForUpdate(candidate.id).orElse(null);
    if(n==null || n.deletedAt==null || !n.deletedAt.isBefore(cutoff)) return;
    for(var a:attachments.findByNoteIdOrderById(n.id)) {
     var locked=attachments.findForPublication(a.id).orElseThrow();
     locked.noteId=null; locked.retired=true; locked.createdAt=n.deletedAt; attachments.save(locked);
    }
    attachments.flush(); notes.delete(n); notes.flush();
   }); } catch(Exception e) { warn(); }
  }
  for(var candidate:attachments.findCleanupCandidates(cutoff,PageRequest.of(0,100))) {
   try { transaction.executeWithoutResult(status->{
    var a=attachments.findForPublication(candidate.id).orElse(null);
    if(a==null || a.noteId!=null || a.createdAt.isAfter(cutoff) || drafts.referencesAttachment(a.id)) return;
    Path file=root.resolve(a.storageKey).normalize();
    if(!file.startsWith(root) || file.equals(root)) throw new IllegalArgumentException("Invalid attachment path");
    // Disk failure retains metadata for retry. Referenced files are never removed.
    try { Files.deleteIfExists(file); }
    catch(java.io.IOException ex) { throw new UncheckedIOException(ex); }
    attachments.delete(a); attachments.flush();
   }); } catch(Exception e) { warn(); }
  }
 }
}
