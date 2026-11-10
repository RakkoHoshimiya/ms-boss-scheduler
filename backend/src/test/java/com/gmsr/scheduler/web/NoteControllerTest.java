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
}
