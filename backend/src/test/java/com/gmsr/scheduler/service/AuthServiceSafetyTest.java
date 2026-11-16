package com.gmsr.scheduler.service;

import com.gmsr.scheduler.domain.AppUser;
import com.gmsr.scheduler.dto.ApiDtos.*;
import com.gmsr.scheduler.repository.*;
import com.gmsr.scheduler.security.*;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceSafetyTest {
 @Mock AppUserRepository users;
 @Mock PlayerRepository players;
 @Mock RefreshTokenService refresh;
 private final PasswordEncoder passwords = new BCryptPasswordEncoder(4);
 private AuthService service;
 private AppUser user(long id, boolean administrator, boolean enabled) {
  AppUser user=new AppUser();user.setId(id);user.setUsername("user"+id);user.setDisplayName("name"+id);
  user.setRole(administrator?AppUser.Role.ADMIN:AppUser.Role.VIEWER);user.setEnabled(enabled);
  user.setPasswordHash(passwords.encode("current-password"));return user;
 }
 @BeforeEach void setup() {
  service=new AuthService(users,players,passwords,mock(LoginAttemptService.class),refresh,"default-password",7200);
 }
 private void target(AppUser target,List<AppUser> all) {
  when(users.findByRoleForUpdate(AppUser.Role.ADMIN)).thenReturn(all.stream().filter(u->u.getRole()==AppUser.Role.ADMIN).toList());
  when(users.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));
  lenient().when(users.findAll()).thenReturn(all);
 }
 @Test void lastEnabledAdministratorCannotBeDemotedDisabledOrDeleted() {
  AppUser only=user(1,true,true),disabled=user(2,true,false);target(only,List.of(only,disabled));
  assertThrows(BusinessException.class,()->service.updateUser(1L,new UserUpdateRequest("name1","VIEWER",null,null,null)));
  assertThrows(BusinessException.class,()->service.updateUser(1L,new UserUpdateRequest("name1",null,false,null,null)));
  assertThrows(BusinessException.class,()->service.deleteUser(1L));
  assertEquals(AppUser.Role.ADMIN,only.getRole());assertTrue(only.isEnabled());
  verify(users,never()).save(any());verify(users,never()).delete(any());verifyNoInteractions(refresh);
 }
 @Test void anotherEnabledAdministratorAllowsRemovalAndLocksAdministratorsBeforeTarget() {
  AppUser first=user(1,true,true),second=user(2,true,true);target(first,List.of(first,second));
  try(var sessions=mockStatic(StpUtil.class)) {
   service.deleteUser(1L);verify(users).delete(first);verify(refresh).revokeAll(1L);
  }
  InOrder order=inOrder(users);order.verify(users).findByRoleForUpdate(AppUser.Role.ADMIN);order.verify(users).findByIdForUpdate(1L);
 }
 @Test void permissionChangesAndDisableReenableInvalidatePreviousSessionVersions() {
  AppUser target=user(1,false,true);target(target,List.of(target));
  try(var sessions=mockStatic(StpUtil.class)) {
   service.updateUser(1L,new UserUpdateRequest("name1",null,false,null,null));
   assertEquals(1,target.getSessionVersion());assertFalse(target.isEnabled());
   service.updateUser(1L,new UserUpdateRequest("name1",null,true,false,null));
   assertEquals(2,target.getSessionVersion());assertTrue(target.isEnabled());
   sessions.verify(()->StpUtil.logout(1L),times(2));verify(refresh,times(2)).revokeAll(1L);
  }
 }
 @Test void ordinaryRenameKeepsSessionButStillReadsUnderLock() {
  AppUser target=user(1,false,true);target(target,List.of(target));
  service.updateUser(1L,new UserUpdateRequest("renamed",null,null,null,null));
  assertEquals(0,target.getSessionVersion());verifyNoInteractions(refresh);verify(users,never()).findById(any());
 }
 @Test void oldPasswordCannotOverrideAnAlreadyResetLockedAccount() {
  AppUser current=user(1,false,true);current.setPasswordHash(passwords.encode("admin-reset-password"));
  String hash=current.getPasswordHash();when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
  assertThrows(BusinessException.class,()->service.changePassword(1L,new ChangePasswordRequest("current-password","attacker-password")));
  assertEquals(hash,current.getPasswordHash());verify(users,never()).findById(any());verifyNoInteractions(refresh);
 }
 @Test void passwordChangeIncrementsVersionOnTheSameLockedEntity() {
  AppUser current=user(1,false,true);when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(current));
  try(var sessions=mockStatic(StpUtil.class)) {
   service.changePassword(1L,new ChangePasswordRequest("current-password","new-password"));
   assertTrue(passwords.matches("new-password",current.getPasswordHash()));assertEquals(1,current.getSessionVersion());
   verify(users,times(1)).findByIdForUpdate(1L);verify(users,never()).findById(any());verify(refresh).revokeAll(1L);
  }
 }
}
