package com.gmsr.scheduler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gmsr.scheduler.domain.AppUser;
import com.gmsr.scheduler.domain.Player;
import com.gmsr.scheduler.dto.ApiDtos.UserUpdateRequest;
import com.gmsr.scheduler.dto.ApiDtos.UserUpsertRequest;
import com.gmsr.scheduler.dto.ApiDtos.UserView;
import com.gmsr.scheduler.repository.AppUserRepository;
import com.gmsr.scheduler.repository.PlayerRepository;
import com.gmsr.scheduler.security.LoginAttemptService;
import com.gmsr.scheduler.security.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 账号编辑路径的回归测试。
 *
 * <p><b>背景（线上真实故障）</b>：以前的「编辑账号」接口复用了新建时的 DTO，
 * 而前端编辑时**不传 playerId**。服务端把「playerId 为 null」当成「解绑群友」，
 * 于是管理员只是改个显示名或重置密码，账号与群友的绑定就被静默清掉了：
 * 该账号随即失去「我的角色 / 我的车次」，群里看到的现象是「我的车次突然空了」。
 *
 * <p>现在拆成 {@link UserUpsertRequest}（新建，可指定绑定）与
 * {@link UserUpdateRequest}（编辑，**结构上就没有 playerId 字段**）。
 * 这个测试锁住：编辑不会改动绑定关系。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceBindingTest {

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PlayerRepository playerRepository;
    @Mock
    private LoginAttemptService loginAttemptService;
    @Mock
    private RefreshTokenService refreshTokenService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, playerRepository, passwordEncoder,
                loginAttemptService, refreshTokenService,
                "<DEFAULT_PLAYER_PASSWORD>", 7200L);
        // 通用桩：保存即返回入参
        lenient().when(userRepository.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(playerRepository.save(any(Player.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** 造一个已绑定群友的账号。 */
    private AppUser boundUser(long userId, String username, String displayName, String playerNickname) {
        Player player = new Player();
        player.setId(10L);
        player.setNickname(playerNickname);
        player.setQq("123456789");

        AppUser user = new AppUser();
        user.setId(userId);
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setRole(AppUser.Role.VIEWER);
        user.setEnabled(true);
        user.setPasswordHash(passwordEncoder.encode("old-password"));
        user.setPlayer(player);
        return user;
    }

    @Test
    @DisplayName("编辑账号（只改显示名）不得解绑群友 —— 这是之前线上静默丢绑定的根因")
    void updateUserKeepsPlayerBinding() {
        AppUser user = boundUser(1L, "123456789", "群友A", "群友A");
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(user));
        when(userRepository.findAll()).thenReturn(java.util.List.of(user));
        when(playerRepository.findByNickname("群友A2")).thenReturn(java.util.Optional.empty());

        // 注意：UserUpdateRequest 根本没有 playerId 字段，所以「前端忘了传」不再有歧义
        UserView view = authService.updateUser(1L,
                new UserUpdateRequest("群友A2", null, null, null, null));

        assertNotNull(user.getPlayer(), "编辑账号绝不允许解绑群友");
        assertEquals(10L, user.getPlayer().getId(), "绑定的群友不能被换掉");
        assertEquals("群友A2", user.getPlayer().getNickname(), "显示名应与群友名同步");
        assertEquals("群友A2", view.displayName());
        assertEquals(10L, view.playerId(), "返回给前端的 playerId 必须还在");
    }

    @Test
    @DisplayName("编辑账号（重置密码）同样不得解绑群友")
    void passwordResetKeepsBinding() {
        AppUser user = boundUser(2L, "223456789", "群友B", "群友B");
        when(userRepository.findById(2L)).thenReturn(java.util.Optional.of(user));
        when(userRepository.findAll()).thenReturn(java.util.List.of(user));

        authService.updateUser(2L, new UserUpdateRequest("群友B", null, true, true, "new-password-123"));

        assertNotNull(user.getPlayer(), "改密码不允许影响绑定关系");
        assertTrue(user.isMustChangePassword(), "管理员改密码应要求本人再设一次新密码");
    }

    @Test
    @DisplayName("编辑账号改为已被占用的群友名时，必须明确报错而不是撞唯一键 500")
    void updateUserRejectsTakenPlayerName() {
        AppUser user = boundUser(3L, "323456789", "群友C", "群友C");
        Player other = new Player();
        other.setId(99L);
        other.setNickname("群友Z");
        when(userRepository.findById(3L)).thenReturn(java.util.Optional.of(user));
        when(userRepository.findAll()).thenReturn(java.util.List.of(user));
        when(playerRepository.findByNickname("群友Z")).thenReturn(java.util.Optional.of(other));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.updateUser(3L, new UserUpdateRequest("群友Z", null, null, null, null)));
        assertTrue(ex.getMessage().contains("已经存在"), "提示应说明名称被占用，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("新建账号：用户名是合法 QQ 才写进 player.qq，否则留空（避免非法 QQ 与唯一键冲突）")
    void createUserWritesQqOnlyWhenValid() {
        when(userRepository.findByUsername("not-a-qq")).thenReturn(java.util.Optional.empty());
        when(playerRepository.findByNickname("新建群友")).thenReturn(java.util.Optional.empty());
        // 模拟自增主键：第二次保存（绑定 player 到 user）时已经有 id
        org.mockito.Mockito.when(userRepository.save(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser arg = invocation.getArgument(0);
            arg.setId(4L);
            return arg;
        });

        authService.createUser(new UserUpsertRequest(
                "not-a-qq", "password-123", "新建群友", "VIEWER", null, null, null));

        // 提交给 playerRepository.save 的 Player 里，qq 必须为空
        org.mockito.Mockito.verify(playerRepository).save(org.mockito.ArgumentMatchers.argThat(
                player -> player.getQq() == null && "新建群友".equals(player.getNickname())));
    }

    @Test
    @DisplayName("新建账号：用户名本身就是 QQ 号时，写入 player.qq")
    void createUserWritesQqWhenValid() {
        when(userRepository.findByUsername("123456789")).thenReturn(java.util.Optional.empty());
        when(playerRepository.findByNickname("群友Q")).thenReturn(java.util.Optional.empty());
        when(userRepository.save(any(AppUser.class))).thenAnswer(invocation -> {
            AppUser arg = invocation.getArgument(0);
            arg.setId(5L);
            return arg;
        });

        authService.createUser(new UserUpsertRequest(
                "123456789", "password-123", "群友Q", "VIEWER", null, null, null));

        org.mockito.Mockito.verify(playerRepository).save(org.mockito.ArgumentMatchers.argThat(
                player -> "123456789".equals(player.getQq())));
    }
}
