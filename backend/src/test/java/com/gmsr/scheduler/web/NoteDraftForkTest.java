package com.gmsr.scheduler.web;

import com.gmsr.scheduler.domain.*;
import com.gmsr.scheduler.repository.*;
import com.gmsr.scheduler.service.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoteDraftForkTest {
 @TempDir Path directory;
 NoteRepository notes=mock(NoteRepository.class);
 NoteAttachmentRepository files=mock(NoteAttachmentRepository.class);
 NoteDraftRepository drafts=mock(NoteDraftRepository.class);
 NoteController controller;
 @BeforeEach void setup() {
  controller=new NoteController(notes,files,drafts,directory.toString());
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L,"",List.of()));
  when(drafts.saveAndFlush(any())).thenAnswer(invocation->invocation.getArgument(0));
 }
 @AfterEach void clear() {
  SecurityContextHolder.clearContext();
  if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
 }
 NoteAttachment image(String id,long owner) throws Exception {
  NoteAttachment a=new NoteAttachment();a.id=id;a.name="guide.png";a.mediaType="image/png";a.storageKey=id+".png";
  a.uploadedBy=owner;a.createdAt=Instant.now();a.size=6;Files.writeString(directory.resolve(a.storageKey),"pixels");
  when(files.findForPublication(id)).thenReturn(Optional.of(a));return a;
 }
 NoteController.DraftEdit edit(List<String> ids,String body) {
  return new NoteController.DraftEdit(99L,1L,3L,"recovered",body,true,ids);
 }
 List<Path> diskFiles() throws Exception {try(var stream=Files.walk(directory)){return stream.filter(Files::isRegularFile).toList();}}
 @Test void forksADeletedSourceReferencedByAnOwnedDraftWithoutSharingFiles() throws Exception {
  var source=image("image",8);source.noteId=99L;source.retired=true;
  when(drafts.ownsActiveAttachment("image",7L)).thenReturn(true);
  var result=controller.forkDraft(edit(List.of("image"),"before[[image:image]]after"));
  assertNull(result.noteId());assertNull(result.sourceVersion());assertTrue(result.pinned());
  assertEquals("recovered",result.title());assertEquals(1,result.attachments().size());
  var attachment=result.attachments().getFirst();assertNotEquals(source.id,attachment.id());
  assertEquals("before[[image:"+attachment.id()+"]]after",result.content());
  var capture=ArgumentCaptor.forClass(NoteAttachment.class);verify(files).saveAndFlush(capture.capture());
  var copied=capture.getValue();assertEquals(7L,copied.uploadedBy);assertNull(copied.noteId);assertFalse(copied.retired);
  assertNotEquals(source.storageKey,copied.storageKey);assertEquals("pixels",Files.readString(directory.resolve(copied.storageKey)));
  assertEquals("pixels",Files.readString(directory.resolve(source.storageKey)));assertEquals(99L,source.noteId);assertTrue(source.retired);
  verifyNoInteractions(notes);verify(drafts,never()).findForUpdate(any());
 }
 @Test void textOnlyForkDoesNotDependOnAnExistingOrCurrentSourceNote() throws Exception {
  var result=controller.forkDraft(edit(List.of(),"unsaved recovery text"));
  assertNull(result.noteId());assertEquals("unsaved recovery text",result.content());assertTrue(result.attachments().isEmpty());
  verifyNoInteractions(notes);assertTrue(diskFiles().isEmpty());
 }
 @Test void rejectsAnotherUsersPrivateOrExpiredUploadAndUnselectedImageMarkers() throws Exception {
  var source=image("image",8);
  assertThrows(NotFoundException.class,()->controller.forkDraft(edit(List.of("image"),"[[image:image]]")));
  source.uploadedBy=7L;source.createdAt=Instant.now().minus(Duration.ofDays(8));
  assertThrows(NotFoundException.class,()->controller.forkDraft(edit(List.of("image"),"[[image:image]]")));
  assertThrows(BusinessException.class,()->controller.forkDraft(edit(List.of(),"[[image:image]]")));
  verify(files,never()).saveAndFlush(any());verify(drafts,never()).saveAndFlush(any());assertEquals(1,diskFiles().size());
 }
 @Test void failureAfterCopyRemovesOnlyNewFilesAndKeepsTheOriginal() throws Exception {
  image("image",7);when(drafts.saveAndFlush(any())).thenThrow(new IllegalStateException("database unavailable"));
  assertThrows(IllegalStateException.class,()->controller.forkDraft(edit(List.of("image"),"[[image:image]]")));
  assertEquals(List.of(directory.resolve("image.png")),diskFiles());assertEquals("pixels",Files.readString(directory.resolve("image.png")));
 }
 @Test void commitRollbackRemovesCopiedFilesButNeverSourceFiles() throws Exception {
  image("image",7);TransactionSynchronizationManager.initSynchronization();
  controller.forkDraft(edit(List.of("image"),"[[image:image]]"));assertEquals(2,diskFiles().size());
  for(var synchronization:TransactionSynchronizationManager.getSynchronizations()) synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
  assertEquals(List.of(directory.resolve("image.png")),diskFiles());
 }
 @Test void quotaAndRepeatedAttachmentChecksRunBeforeCopying() throws Exception {
  when(drafts.countByOwnerIdAndDeletedAtIsNull(7L)).thenReturn(20L);
  assertThrows(BusinessException.class,()->controller.forkDraft(edit(List.of(),"body")));
  when(drafts.countByOwnerIdAndDeletedAtIsNull(7L)).thenReturn(0L);
  assertThrows(BusinessException.class,()->controller.forkDraft(edit(List.of("image","image"),"body")));
  assertTrue(diskFiles().isEmpty());verify(files,never()).saveAndFlush(any());
 }
}
