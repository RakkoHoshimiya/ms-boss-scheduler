package com.gmsr.scheduler.web;
import com.gmsr.scheduler.domain.*;
import com.gmsr.scheduler.repository.*;
import com.gmsr.scheduler.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class NoteControllerTest {
 @TempDir Path directory;
 NoteRepository notes=mock(NoteRepository.class);
 NoteAttachmentRepository files=mock(NoteAttachmentRepository.class);
 NoteController controller;
 @BeforeEach void setup(){controller=new NoteController(notes,files,directory.toString());when(notes.findById(1L)).thenReturn(Optional.of(new Note()));when(files.findByNoteIdOrderById(1L)).thenReturn(List.of());}
 @Test void uniqueNamesAndSafePath() throws Exception {
  var file=new MockMultipartFile("file","../../guide.txt","text/plain","tips".getBytes());
  var first=controller.upload(1L,file);var second=controller.upload(1L,file);
  assertNotEquals(first.id(),second.id());assertEquals("guide.txt",first.name());
  try(var paths=Files.walk(directory)){assertEquals(2,paths.filter(Files::isRegularFile).count());}
 }
 @Test void rejectsActiveContentAndMissingNote() {
  assertThrows(BusinessException.class,()->controller.upload(1L,new MockMultipartFile("file","script.html","text/html","hello".getBytes())));
  assertThrows(NotFoundException.class,()->controller.upload(2L,new MockMultipartFile("file","a.txt","text/plain","hello".getBytes())));
 }
 @Test void enforcesAttachmentLimit(){when(files.findByNoteIdOrderById(1L)).thenReturn(Collections.nCopies(12,new NoteAttachment()));assertThrows(BusinessException.class,()->controller.upload(1L,new MockMultipartFile("file","a.txt","text/plain","hello".getBytes())));}
 @Test void preventsCrossNoteDownload(){NoteAttachment a=new NoteAttachment();a.noteId=2L;when(files.findById("a")).thenReturn(Optional.of(a));assertThrows(NotFoundException.class,()->controller.download(1L,"a"));}
 @Test void cleansFileOnDatabaseFailure() throws Exception {
  doThrow(new IllegalStateException("db down")).when(files).saveAndFlush(any());
  assertThrows(IllegalStateException.class,()->controller.upload(1L,new MockMultipartFile("file","a.txt","text/plain","hello".getBytes())));
  try(var paths=Files.walk(directory)){assertEquals(0,paths.filter(Files::isRegularFile).count());}
 }

 @AfterEach void clearUser(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
 private void login(long id){org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(id,"",List.of()));}
 private NoteAttachment staged(String id,long owner,String type){NoteAttachment a=new NoteAttachment();a.id=id;a.uploadedBy=owner;a.createdAt=java.time.Instant.now();a.mediaType=type;when(files.findById(id)).thenReturn(Optional.of(a));when(files.findForPublication(id)).thenReturn(Optional.of(a));return a;}
 @Test void stageBeforePublishDoesNotCreateNote() throws Exception {
  login(7);controller.stage(new MockMultipartFile("file","a.txt","text/plain","tips".getBytes()));
  var capture=org.mockito.ArgumentCaptor.forClass(NoteAttachment.class);verify(files).saveAndFlush(capture.capture());
  assertNull(capture.getValue().noteId);assertEquals(7L,capture.getValue().uploadedBy);verify(notes,never()).saveAndFlush(any());
 }
 @Test void unpublishedUploadsArePrivate(){login(7);staged("a",8,"image/png");assertThrows(NotFoundException.class,()->controller.stagedDownload("a"));assertThrows(NotFoundException.class,()->controller.create(new NoteController.Edit("guide","[[image:a]]",false,List.of("a"))));}
 @Test void publishBindsImagesAndFooterFilesTogether(){
  login(7);var image=staged("image",7,"image/png");var footer=staged("pdf",7,"application/octet-stream");
  when(notes.saveAndFlush(any())).thenAnswer(call->{Note n=call.getArgument(0);n.id=44L;return n;});
  Note n=controller.create(new NoteController.Edit("guide","before[[image:image]]after",false,List.of("image","pdf")));
  assertEquals(44L,image.noteId);assertEquals(44L,footer.noteId);assertEquals("before[[image:image]]after",n.content);
 }
 @Test void rejectsForeignNoteAndNonImageReferences(){
  login(7);var image=staged("image",7,"image/png");image.noteId=99L;
  assertThrows(BusinessException.class,()->controller.create(new NoteController.Edit("guide","[[image:image]]",false,List.of("image"))));
  staged("pdf",7,"application/octet-stream");assertThrows(BusinessException.class,()->controller.create(new NoteController.Edit("guide","[[image:pdf]]",false,List.of("pdf"))));
  verify(notes,never()).saveAndFlush(any());
 }
 @Test void failedSaveDoesNotConsumeStagedUpload(){
  login(7);var image=staged("image",7,"image/png");when(notes.saveAndFlush(any())).thenThrow(new IllegalStateException("db down"));
  assertThrows(IllegalStateException.class,()->controller.create(new NoteController.Edit("guide","[[image:image]]",false,List.of("image"))));assertNull(image.noteId);
 }
 @Test void draftQuotaAndCancelProtection(){
  login(7);when(files.countByNoteIdIsNullAndUploadedBy(7L)).thenReturn(24L);assertThrows(BusinessException.class,()->controller.stage(new MockMultipartFile("file","a.txt","text/plain","tips".getBytes())));
  var published=staged("image",7,"image/png");published.noteId=1L;assertThrows(BusinessException.class,()->controller.discard("image"));verify(files,never()).delete(any());
 }
 @Test void removedPublishedAttachmentsWaitForSuccessfulSave(){
  login(7);Note n=new Note();n.id=1L;when(notes.findById(1L)).thenReturn(Optional.of(n));
  var image=staged("image",7,"image/png");image.noteId=1L;when(files.findByNoteIdOrderById(1L)).thenReturn(List.of(image));when(notes.saveAndFlush(any())).thenReturn(n);
  controller.update(1L,new NoteController.Edit("guide","text",false,List.of()));assertNull(image.noteId);verify(files,never()).delete(any());
 }
}
