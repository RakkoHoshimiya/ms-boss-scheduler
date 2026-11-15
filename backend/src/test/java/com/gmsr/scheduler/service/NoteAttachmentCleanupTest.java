package com.gmsr.scheduler.service;
import com.gmsr.scheduler.domain.NoteAttachment;
import com.gmsr.scheduler.domain.Note;
import com.gmsr.scheduler.domain.NoteDraft;
import com.gmsr.scheduler.repository.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class NoteAttachmentCleanupTest {
 @TempDir Path directory;
 private final PlatformTransactionManager manager=new PlatformTransactionManager(){
  public TransactionStatus getTransaction(TransactionDefinition definition){return new SimpleTransactionStatus();}
  public void commit(TransactionStatus status){} public void rollback(TransactionStatus status){}
 };
 private NoteAttachment file(String key) throws Exception {
  var a=new NoteAttachment();a.id=key;a.storageKey=key;a.createdAt=Instant.now().minus(Duration.ofDays(8));Files.writeString(directory.resolve(key),"test");return a;
 }
 @Test void expiredUnpublishedFileIsRemoved() throws Exception {
  var repo=mock(NoteAttachmentRepository.class);var a=file("expired");
  when(repo.findCleanupCandidates(any(),any())).thenReturn(List.of(a));when(repo.findForPublication(a.id)).thenReturn(Optional.of(a));
  new NoteAttachmentCleanup(repo,mock(NoteRepository.class),mock(NoteDraftRepository.class),manager,directory.toString()).cleanup();assertFalse(Files.exists(directory.resolve(a.storageKey)));verify(repo).delete(a);
 }
 @Test void concurrentPublicationKeepsItsFile() throws Exception {
  var repo=mock(NoteAttachmentRepository.class);var a=file("published");a.noteId=1L;
  when(repo.findCleanupCandidates(any(),any())).thenReturn(List.of(a));when(repo.findForPublication(a.id)).thenReturn(Optional.of(a));
  new NoteAttachmentCleanup(repo,mock(NoteRepository.class),mock(NoteDraftRepository.class),manager,directory.toString()).cleanup();assertTrue(Files.exists(directory.resolve(a.storageKey)));verify(repo,never()).delete(any());
 }
 @Test void diskFailureKeepsMetadataForRetry() throws Exception {
  var repo=mock(NoteAttachmentRepository.class);var a=file("pending");a.storageKey="nonempty";Files.createDirectory(directory.resolve("nonempty"));Files.writeString(directory.resolve("nonempty/child"),"test");
  when(repo.findCleanupCandidates(any(),any())).thenReturn(List.of(a));when(repo.findForPublication(a.id)).thenReturn(Optional.of(a));
  new NoteAttachmentCleanup(repo,mock(NoteRepository.class),mock(NoteDraftRepository.class),manager,directory.toString()).cleanup();verify(repo,never()).delete(any());assertTrue(Files.exists(directory.resolve("nonempty/child")));
 }
 @Test void youngerThanSevenDaysAndReferencedFilesAreKept() throws Exception {
  var repo=mock(NoteAttachmentRepository.class);var drafts=mock(NoteDraftRepository.class);
  var recent=file("recent");recent.createdAt=Instant.now().minus(Duration.ofDays(6));var protectedFile=file("draft-image");
  when(repo.findCleanupCandidates(any(),any())).thenReturn(List.of(recent,protectedFile));
  when(repo.findForPublication(recent.id)).thenReturn(Optional.of(recent));when(repo.findForPublication(protectedFile.id)).thenReturn(Optional.of(protectedFile));
  when(drafts.referencesAttachment(protectedFile.id)).thenReturn(true);
  new NoteAttachmentCleanup(repo,mock(NoteRepository.class),drafts,manager,directory.toString()).cleanup();
  assertTrue(Files.exists(directory.resolve(recent.storageKey)));assertTrue(Files.exists(directory.resolve(protectedFile.storageKey)));verify(repo,never()).delete(any());
 }
 @Test void expiredRecycleBinContentIsPurgedButRestoredContentIsKept() {
  var repo=mock(NoteAttachmentRepository.class);var notes=mock(NoteRepository.class);var drafts=mock(NoteDraftRepository.class);
  Note expired=new Note();expired.id=1L;expired.deletedAt=Instant.now().minus(Duration.ofDays(8));Note restored=new Note();restored.id=2L;
  NoteDraft draft=new NoteDraft();draft.id="old";draft.deletedAt=expired.deletedAt;
  when(notes.findTop100ByDeletedAtBeforeOrderByDeletedAtAsc(any())).thenReturn(List.of(expired,restored));
  when(notes.findForUpdate(1L)).thenReturn(Optional.of(expired));when(notes.findForUpdate(2L)).thenReturn(Optional.of(restored));
  when(drafts.findTop100ByDeletedAtBeforeOrderByDeletedAtAsc(any())).thenReturn(List.of(draft));when(drafts.findForUpdate("old")).thenReturn(Optional.of(draft));
  new NoteAttachmentCleanup(repo,notes,drafts,manager,directory.toString()).cleanup();
  verify(notes).delete(expired);verify(notes,never()).delete(restored);verify(drafts).delete(draft);
 }
}
