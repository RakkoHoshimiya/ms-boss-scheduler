package com.gmsr.scheduler.service;
import com.gmsr.scheduler.domain.NoteAttachment;
import com.gmsr.scheduler.repository.NoteAttachmentRepository;
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
  var a=new NoteAttachment();a.id=key;a.storageKey=key;a.createdAt=Instant.now().minus(Duration.ofDays(2));Files.writeString(directory.resolve(key),"test");return a;
 }
 @Test void expiredUnpublishedFileIsRemoved() throws Exception {
  var repo=mock(NoteAttachmentRepository.class);var a=file("expired");
  when(repo.findTop100ByNoteIdIsNullAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(any())).thenReturn(List.of(a));when(repo.findForPublication(a.id)).thenReturn(Optional.of(a));
  new NoteAttachmentCleanup(repo,manager,directory.toString()).cleanup();assertFalse(Files.exists(directory.resolve(a.storageKey)));verify(repo).delete(a);
 }
 @Test void concurrentPublicationKeepsItsFile() throws Exception {
  var repo=mock(NoteAttachmentRepository.class);var a=file("published");a.noteId=1L;
  when(repo.findTop100ByNoteIdIsNullAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(any())).thenReturn(List.of(a));when(repo.findForPublication(a.id)).thenReturn(Optional.of(a));
  new NoteAttachmentCleanup(repo,manager,directory.toString()).cleanup();assertTrue(Files.exists(directory.resolve(a.storageKey)));verify(repo,never()).delete(any());
 }
 @Test void diskFailureKeepsMetadataForRetry() throws Exception {
  var repo=mock(NoteAttachmentRepository.class);var a=file("pending");a.storageKey="nonempty";Files.createDirectory(directory.resolve("nonempty"));Files.writeString(directory.resolve("nonempty/child"),"test");
  when(repo.findTop100ByNoteIdIsNullAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(any())).thenReturn(List.of(a));when(repo.findForPublication(a.id)).thenReturn(Optional.of(a));
  new NoteAttachmentCleanup(repo,manager,directory.toString()).cleanup();verify(repo,never()).delete(any());assertTrue(Files.exists(directory.resolve("nonempty/child")));
 }
}
