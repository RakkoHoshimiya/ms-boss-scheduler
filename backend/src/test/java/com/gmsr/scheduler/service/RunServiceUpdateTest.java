package com.gmsr.scheduler.service;

import com.gmsr.scheduler.domain.*;
import com.gmsr.scheduler.dto.RunDtos.RunRequest;
import com.gmsr.scheduler.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunServiceUpdateTest {
 private final BossRunRepository runs=mock(BossRunRepository.class);
 private final RunMemberRepository members=mock(RunMemberRepository.class);
 private final BossRepository bosses=mock(BossRepository.class);
 private final ComboGuard guard=mock(ComboGuard.class);
 private final EntityManager entities=mock(EntityManager.class);
 private final RunService service=new RunService(runs,members,bosses,mock(GameCharacterRepository.class),guard);
 private Boss boss(long id,String combo,String tiers) {
  Boss boss=new Boss();boss.setId(id);boss.setName("boss"+id);boss.setCapacity(6);boss.setTier(BossTier.H);boss.setTiers(tiers);boss.setComboKey(combo);
  BossGroup group=new BossGroup();group.setName("group");boss.setGroup(group);return boss;
 }
 private BossRun run(long id,Boss boss) {
  BossRun run=new BossRun();run.setId(id);run.setBoss(boss);run.setTier(BossTier.H);run.setRunIndex(1);
  run.setScheduled("time"+id);run.setNote("note"+id);run.setStatus(BossRun.Status.PLANNED);return run;
 }
 private List<BossRun> trip() {
  var first=run(1,boss(1,"mystic-7","H,N"));var second=run(2,boss(2,"mystic-7","H,N"));
  List<BossRun> trip=List.of(first,second);when(runs.findById(1L)).thenReturn(Optional.of(first));when(guard.tripOf(first)).thenReturn(trip);
  when(guard.isCombo(first)).thenReturn(true);return trip;
 }
 @BeforeEach void setup(){ReflectionTestUtils.setField(service,"entityManager",entities);}
 @Test void editingComboIndexAndCompletionKeepsTripAlignedWithoutOverwritingSiblingNotes() {
  var trip=trip();service.update(1L,new RunRequest(1L,"H",3,"new time","DONE","new note"));
  for(var row:trip){assertEquals(3,row.getRunIndex());assertEquals(BossTier.H,row.getTier());assertEquals(BossRun.Status.DONE,row.getStatus());verify(entities).refresh(row);}
  assertEquals("time2",trip.get(1).getScheduled());assertEquals("note2",trip.get(1).getNote());
  verify(runs).findAllByIdForUpdate(List.of(1L,2L));verify(runs).saveAll(trip);
 }
 @Test void fixedDifficultyComboRejectsOtherTiersBeforeChangingAnySibling() {
  var trip=trip();assertThrows(BusinessException.class,()->service.update(1L,new RunRequest(1L,"N",3,"changed","DONE","changed")));
  for(var row:trip){assertEquals(1,row.getRunIndex());assertEquals(BossTier.H,row.getTier());assertEquals(BossRun.Status.PLANNED,row.getStatus());}
  verify(runs,never()).saveAll(any());
 }
 @Test void comboCannotBeReboundAsASingleBossRun() {
  var trip=trip();Boss plain=boss(9,null,"H");when(bosses.findById(9L)).thenReturn(Optional.of(plain));
  assertThrows(BusinessException.class,()->service.update(1L,new RunRequest(9L,"H",1,null,null,null)));
  assertEquals(1L,trip.getFirst().getBoss().getId());verify(runs,never()).saveAll(any());
 }
 @Test void ordinaryRunCannotBeInsertedIntoAComboThroughRebinding() {
  BossRun run=run(1,boss(1,null,"H"));when(runs.findById(1L)).thenReturn(Optional.of(run));when(guard.tripOf(run)).thenReturn(List.of(run));
  Boss combo=boss(9,"mystic-7","H");when(bosses.findById(9L)).thenReturn(Optional.of(combo));when(guard.isCombo(combo)).thenReturn(true);
  assertThrows(BusinessException.class,()->service.update(1L,new RunRequest(9L,"H",1,null,null,null)));
  assertEquals(1L,run.getBoss().getId());
 }
 @Test void createFixedComboAtAnotherDifficultyIsRejected() {
  Boss combo=boss(1,"mystic-7","H,N");when(bosses.findById(1L)).thenReturn(Optional.of(combo));
  assertThrows(BusinessException.class,()->service.create(new RunRequest(1L,"N",1,null,null,null)));
  verify(runs,never()).save(any());
 }
 @Test void delayedEditorCannotSplitATripThatMovedBeforeItAcquiredItsLocks() {
  var trip=trip();BossRun first=trip.getFirst();when(guard.tripOf(first)).thenReturn(List.of(first));
  doAnswer(invocation->{ first.setRunIndex(2);return null; }).when(entities).refresh(first);
  assertThrows(ConflictException.class,()->service.update(1L,new RunRequest(1L,"H",3,null,null,null)));
  assertEquals(2,first.getRunIndex());verify(runs,never()).saveAll(any());
 }
}
