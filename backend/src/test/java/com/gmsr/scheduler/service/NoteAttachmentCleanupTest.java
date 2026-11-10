package com.gmsr.scheduler.service;
import com.gmsr.scheduler.domain.NoteAttachment;
import com.gmsr.scheduler.repository.NoteAttachmentRepository;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class NoteAttachmentCleanupTest {
 @TempDir Path directory;
 @Test void expiredUnpublishedFileIsRemoved() throws Exception {
  var repo=mock(NoteAttachmentRepository.class);var a=new NoteAttachment();a.id="expired";a.storageKey="expired.txt";Files.writeString(directory.resolve(a.storageKey),"test");
  when(repo.findByNoteIdIsNullAndCreatedAtBefore(any())).thenReturn(List.of(a));when(repo.deleteExpiredStaged(eq(a.id),any())).thenReturn(1);
  new NoteAttachmentCleanup(repo,directory.toString()).cleanup();assertFalse(Files.exists(directory.resolve(a.storageKey)));
 }
 @Test void concurrentPublicationKeepsItsFile() throws Exception {
  var repo=mock(NoteAttachmentRepository.class);var a=new NoteAttachment();a.id="published";a.storageKey="published.txt";Files.writeString(directory.resolve(a.storageKey),"test");
  when(repo.findByNoteIdIsNullAndCreatedAtBefore(any())).thenReturn(List.of(a));when(repo.deleteExpiredStaged(eq(a.id),any())).thenReturn(0);
  new NoteAttachmentCleanup(repo,directory.toString()).cleanup();assertTrue(Files.exists(directory.resolve(a.storageKey)));
 }
}
