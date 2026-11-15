package com.gmsr.scheduler.web;

import com.gmsr.scheduler.domain.*;
import com.gmsr.scheduler.repository.*;
import com.gmsr.scheduler.service.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoteDraftTest {
 @TempDir Path directory;
 NoteRepository notes=mock(NoteRepository.class);
 NoteAttachmentRepository files=mock(NoteAttachmentRepository.class);
 NoteDraftRepository drafts=mock(NoteDraftRepository.class);
 NoteController controller;
 String id="12345678-1234-4234-8234-123456789012";
 @BeforeEach void setup() {
  controller=new NoteController(notes,files,drafts,directory.toString());
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L,"",List.of()));
  when(drafts.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));
 }
 @AfterEach void clear() { SecurityContextHolder.clearContext(); }
 NoteDraft draft() {
  NoteDraft d=new NoteDraft();d.id=id;d.ownerId=7L;d.title="draft";d.content="body";d.updatedAt=Instant.now();d.version=3;
  when(drafts.findForUpdate(id)).thenReturn(Optional.of(d));return d;
 }
 NoteController.DraftEdit edit(Long version,List<String> ids,String body) { return new NoteController.DraftEdit(null,null,version,"",body,false,ids); }
 @Test void emptyPrivateDraftCanBeSavedWithoutPublishing() {
  var result=controller.saveDraft(id,edit(null,List.of(),""));
  assertEquals(id,result.id());assertEquals("",result.content());verifyNoInteractions(notes);verify(notes,never()).saveAndFlush(any());
 }
 @Test void draftCannotBeReadOrModifiedByAnotherUser() {
  var d=draft();d.ownerId=8L;
  assertThrows(NotFoundException.class,()->controller.saveDraft(id,edit(3L,List.of(),"text")));
  assertThrows(NotFoundException.class,()->controller.deleteDraft(id));
  assertThrows(NotFoundException.class,()->controller.restoreDraft(id));
  controller.draftList();verify(drafts).findByOwnerIdAndDeletedAtIsNullOrderByUpdatedAtDesc(7L);
 }
 @Test void staleTabAndDeletedDraftCannotOverwrite() {
  var d=draft();assertThrows(ConflictException.class,()->controller.saveDraft(id,edit(2L,List.of(),"stale")));
  controller.deleteDraft(id);assertNotNull(d.deletedAt);assertEquals("body",d.content);
  assertThrows(ConflictException.class,()->controller.saveDraft(id,edit(3L,List.of(),"resurrect")));
 }
 @Test void restoresWithinSevenDaysButRejectsExpiredDraft() {
  var d=draft();d.deletedAt=Instant.now().minus(Duration.ofDays(6));controller.restoreDraft(id);assertNull(d.deletedAt);
  d.deletedAt=Instant.now().minus(Duration.ofDays(8));assertThrows(NotFoundException.class,()->controller.restoreDraft(id));
 }
 @Test void draftProtectsOldUploadsButNotOtherUsersUploads() {
  var d=draft();var a=new NoteAttachment();a.id="image";a.mediaType="image/png";a.uploadedBy=7L;a.createdAt=Instant.now().minus(Duration.ofDays(20));
  when(files.findForPublication(a.id)).thenReturn(Optional.of(a));
  when(drafts.ownsActiveAttachment(a.id,7L)).thenReturn(true);
  controller.saveDraft(id,edit(3L,List.of("image"),"[[image:image]]"));assertEquals(List.of(a),d.attachments);
  a.uploadedBy=8L;when(drafts.ownsActiveAttachment(a.id,7L)).thenReturn(false);
  assertThrows(NotFoundException.class,()->controller.saveDraft(id,edit(3L,List.of("image"),"[[image:image]]")));
 }
 @Test void publishingConsumesDraftInSameTransactionAndCannotRepeat() {
  var d=draft();when(notes.saveAndFlush(any())).thenAnswer(i->{Note n=i.getArgument(0);n.id=1L;return n;});
  controller.create(new NoteController.Edit("guide","body",false,List.of(),null,id,3L));
  assertNotNull(d.deletedAt);assertTrue(d.completed);
  assertThrows(ConflictException.class,()->controller.create(new NoteController.Edit("guide","body",false,List.of(),null,id,3L)));
 }
 @Test void deletingNoteKeepsBodyAndAttachmentsForRestoration() {
  Note n=new Note();n.id=1L;n.title="guide";n.content="body";when(notes.findForUpdate(1L)).thenReturn(Optional.of(n));when(notes.findById(1L)).thenReturn(Optional.of(n));
  controller.delete(1L);Instant deleted=n.deletedAt;controller.delete(1L);assertEquals(deleted,n.deletedAt);
  assertEquals("body",n.content);assertThrows(NotFoundException.class,()->controller.detail(1L));
  controller.restore(1L);assertNull(n.deletedAt);
  n.deletedAt=Instant.now().minus(Duration.ofDays(8));assertThrows(NotFoundException.class,()->controller.restore(1L));verify(files,never()).delete(any());
 }
}
