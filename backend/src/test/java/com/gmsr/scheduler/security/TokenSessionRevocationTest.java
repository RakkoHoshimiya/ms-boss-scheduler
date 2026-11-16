package com.gmsr.scheduler.security;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.gmsr.scheduler.domain.AppUser;
import com.gmsr.scheduler.dto.ApiDtos.LoginRequest;
import com.gmsr.scheduler.repository.AppUserRepository;
import com.gmsr.scheduler.repository.PlayerRepository;
import com.gmsr.scheduler.service.AuthService;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenSessionRevocationTest {
 @AfterEach void clear() { SecurityContextHolder.clearContext(); }

 @Test void aNewLoginCannotReviveAnOldTokenWhoseRedisRevocationFailed() throws Exception {
  AppUser user=new AppUser();user.setId(7L);user.setUsername("test-user");user.setDisplayName("test-user");
  var passwords=new BCryptPasswordEncoder(4);user.setPasswordHash(passwords.encode("initial-password"));
  var users=mock(AppUserRepository.class);var refresh=mock(RefreshTokenService.class);
  when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
  when(users.findByUsernameForUpdate("test-user")).thenReturn(Optional.of(user));
  when(users.findById(7L)).thenReturn(Optional.of(user));
  var service=new AuthService(users,mock(PlayerRepository.class),passwords,mock(LoginAttemptService.class),refresh,"reset-password",7200);
  SaSession oldTokenSession=mock(SaSession.class),newTokenSession=mock(SaSession.class);
  AtomicReference<Object> newVersion=new AtomicReference<>();
  when(oldTokenSession.get(AuthService.SESSION_VERSION_KEY)).thenReturn(0L);
  when(newTokenSession.get(AuthService.SESSION_VERSION_KEY)).thenAnswer(invocation->newVersion.get());
  when(newTokenSession.set(eq(AuthService.SESSION_VERSION_KEY),any())).thenAnswer(invocation->{newVersion.set(invocation.getArgument(1));return newTokenSession;});
  Map<String,SaSession> tokens=Map.of("old-token",oldTokenSession,"new-token",newTokenSession);
  AtomicReference<String> currentToken=new AtomicReference<>("old-token");
  try(var stp=mockStatic(StpUtil.class)) {
   stp.when(() -> StpUtil.logout(7L)).thenThrow(new IllegalStateException("Redis unavailable during reset"));
   stp.when(StpUtil::getTokenSession).thenAnswer(invocation->tokens.get(currentToken.get()));
   stp.when(StpUtil::getTokenValue).thenAnswer(invocation->currentToken.get());
   stp.when(StpUtil::isLogin).thenReturn(true);
   stp.when(StpUtil::getLoginIdDefaultNull).thenReturn(7L);
   stp.when(() -> StpUtil.login(7L)).thenAnswer(invocation->{currentToken.set("new-token");return null;});
   service.resetPassword(7L);
   assertEquals(1,user.getSessionVersion());
   service.login(new LoginRequest("test-user","reset-password"),"127.0.0.1");
   assertEquals(1L,newTokenSession.get(AuthService.SESSION_VERSION_KEY));
   assertEquals(0L,oldTokenSession.get(AuthService.SESSION_VERSION_KEY));
   stp.verify(StpUtil::getSession,never());
   var filter=new SaTokenAuthFilter(users);
   currentToken.set("old-token");
   filter.doFilter(new MockHttpServletRequest("GET","/api/runs"),new MockHttpServletResponse(),
     (request,response)->assertNull(SecurityContextHolder.getContext().getAuthentication()));
   stp.verify(() -> StpUtil.logoutByTokenValue("old-token"));
   SecurityContextHolder.clearContext();currentToken.set("new-token");
   filter.doFilter(new MockHttpServletRequest("GET","/api/auth/me"),new MockHttpServletResponse(),
     (request,response)->assertNotNull(SecurityContextHolder.getContext().getAuthentication()));
   stp.verify(() -> StpUtil.logoutByTokenValue("new-token"),never());
  }
 }
}
