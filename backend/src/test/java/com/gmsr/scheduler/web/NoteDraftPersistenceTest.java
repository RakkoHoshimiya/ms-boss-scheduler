package com.gmsr.scheduler.web;

import com.gmsr.scheduler.domain.*;
import com.gmsr.scheduler.repository.*;
import com.gmsr.scheduler.security.*;
import com.gmsr.scheduler.service.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises V10, repository joins, version checks and cleanup against PostgreSQL in CI. */
@EnabledIfEnvironmentVariable(named="GMSR_INTEGRATION_DB",matches="1")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="gmsr.voice.enabled=false")
@Transactional
class NoteDraftPersistenceTest {
 @MockitoBean SeedDataImporter seed;
 @MockitoBean DataMigrator migrator;
 @MockitoBean AdminAccountInitializer admin;
 @MockitoBean PlayerAccountInitializer players;
 @MockitoBean SecurityStartupCheck startup;
 @MockitoBean WeeklyStatusResetService weekly;
 @MockitoBean NoteAttachmentCleanup scheduledCleanup;
 @Autowired NoteRepository notes;
 @Autowired NoteDraftRepository drafts;
 @Autowired NoteAttachmentRepository attachments;
 @Autowired PlatformTransactionManager manager;
 @TempDir Path directory;
 NoteController controller;
 @BeforeEach void setup() {
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(70001L,"",List.of()));
  controller=new NoteController(notes,attachments,drafts,directory.toString());
 }
 @AfterEach void clear() { SecurityContextHolder.clearContext(); }
 NoteAttachment file() throws Exception {
  NoteAttachment a=new NoteAttachment();a.id=UUID.randomUUID().toString();a.name="draft.txt";a.storageKey=a.id+".txt";a.mediaType="application/octet-stream";a.uploadedBy=70001L;a.createdAt=Instant.now().minus(Duration.ofDays(20));a.size=4;
  Files.writeString(directory.resolve(a.storageKey),"body");return attachments.saveAndFlush(a);
 }
 @Test void draftReferencesProtectFilesAndDeletedDraftPurgesAfterSevenDays() throws Exception {
  var a=file();a.createdAt=Instant.now();attachments.saveAndFlush(a);
  String id=UUID.randomUUID().toString();
  var saved=controller.saveDraft(id,new NoteController.DraftEdit(null,null,null,"draft","body",false,List.of(a.id)));
  assertTrue(drafts.ownsActiveAttachment(a.id,70001L));assertFalse(drafts.ownsActiveAttachment(a.id,70002L));
  a.createdAt=Instant.now().minus(Duration.ofDays(20));attachments.saveAndFlush(a);
  var cleanup=new NoteAttachmentCleanup(attachments,notes,drafts,manager,directory.toString());cleanup.cleanup();
  assertTrue(Files.exists(directory.resolve(a.storageKey)));assertTrue(attachments.existsById(a.id));
  controller.deleteDraft(id);var d=drafts.findById(id).orElseThrow();d.deletedAt=Instant.now().minus(Duration.ofDays(6));drafts.saveAndFlush(d);
  cleanup.cleanup();assertTrue(drafts.existsById(id));
  d.deletedAt=Instant.now().minus(Duration.ofDays(8));drafts.saveAndFlush(d);cleanup.cleanup();
  assertFalse(drafts.existsById(id));assertFalse(attachments.existsById(a.id));assertFalse(Files.exists(directory.resolve(a.storageKey)));
  assertEquals(0,saved.version());
 }
 @Test void softDeletedNoteRetainsBodyAndFileUntilExpiry() throws Exception {
  Note n=new Note();n.title="guide";n.content="body";n.updatedAt=Instant.now();n=notes.saveAndFlush(n);
  var a=file();a.noteId=n.id;attachments.saveAndFlush(a);
  controller.delete(n.id);assertEquals("body",notes.findById(n.id).orElseThrow().content);
  assertFalse(controller.list().stream().anyMatch(note->note.title.equals("guide")));
  controller.restore(n.id);assertNull(notes.findById(n.id).orElseThrow().deletedAt);
  controller.delete(n.id);n.deletedAt=Instant.now().minus(Duration.ofDays(8));notes.saveAndFlush(n);
  new NoteAttachmentCleanup(attachments,notes,drafts,manager,directory.toString()).cleanup();
  assertFalse(notes.existsById(n.id));assertFalse(attachments.existsById(a.id));assertFalse(Files.exists(directory.resolve(a.storageKey)));
 }
}
