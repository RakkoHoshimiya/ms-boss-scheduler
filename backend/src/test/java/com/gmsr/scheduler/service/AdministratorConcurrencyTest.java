package com.gmsr.scheduler.service;

import cn.dev33.satoken.stp.StpUtil;
import com.gmsr.scheduler.domain.AppUser;
import com.gmsr.scheduler.dto.ApiDtos.UserUpdateRequest;
import com.gmsr.scheduler.repository.AppUserRepository;
import com.gmsr.scheduler.security.LoginAttemptService;
import com.gmsr.scheduler.security.RefreshTokenService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

@DataJpaTest
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import(AuthService.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named="GMSR_INTEGRATION_DB",matches="1")
class AdministratorConcurrencyTest {
 @Autowired AuthService service;
 @Autowired AppUserRepository users;
 @MockitoBean PasswordEncoder passwords;
 @MockitoBean LoginAttemptService attempts;
 @MockitoBean RefreshTokenService refresh;

 @Test void simultaneousDemotionsLeaveOneEnabledAdministrator() throws Exception {
  String tag="admin-race-"+UUID.randomUUID();
  List<AppUser> fixture=List.of(administrator(tag+"-a"),administrator(tag+"-b"));
  var executor=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
  try {
   assertEquals(2,users.findAll().stream().filter(user->user.getRole()==AppUser.Role.ADMIN && user.isEnabled()).count(),
     "The isolated integration schema must start with only this fixture's two administrators");
   List<Future<Boolean>> changes=fixture.stream().map(user->executor.submit(()->{
    barrier.await(10,TimeUnit.SECONDS);
    try(var sessions=mockStatic(StpUtil.class)) {
     try {service.updateUser(user.getId(),new UserUpdateRequest(user.getDisplayName(),"VIEWER",null,null,null));return true;}
     catch(BusinessException expected) {assertTrue(expected.getMessage().contains("管理员"));return false;}
    }
   })).toList();
   int success=0;for(var change:changes)if(change.get(15,TimeUnit.SECONDS))success++;
   assertEquals(1,success);
   assertEquals(1,users.findAllById(fixture.stream().map(AppUser::getId).toList()).stream()
     .filter(user->user.getRole()==AppUser.Role.ADMIN && user.isEnabled()).count());
  } finally {
   executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS);
   users.deleteAllByIdInBatch(fixture.stream().map(AppUser::getId).toList());
  }
 }
 private AppUser administrator(String name) {
  AppUser user=new AppUser();user.setUsername(name);user.setDisplayName(name);user.setPasswordHash("not-a-login-fixture");
  user.setRole(AppUser.Role.ADMIN);user.setEnabled(true);return users.saveAndFlush(user);
 }
}
