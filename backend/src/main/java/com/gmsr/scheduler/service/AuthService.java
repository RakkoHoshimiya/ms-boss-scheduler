package com.gmsr.scheduler.service;

import com.gmsr.scheduler.domain.AppUser;
import com.gmsr.scheduler.domain.Player;
import com.gmsr.scheduler.dto.ApiDtos.ChangePasswordRequest;
import com.gmsr.scheduler.dto.ApiDtos.LoginRequest;
import com.gmsr.scheduler.dto.ApiDtos.LoginResponse;
import com.gmsr.scheduler.dto.ApiDtos.ResetPasswordResponse;
import com.gmsr.scheduler.dto.ApiDtos.UserUpsertRequest;
import com.gmsr.scheduler.dto.ApiDtos.UserView;
import com.gmsr.scheduler.repository.AppUserRepository;
import com.gmsr.scheduler.repository.PlayerRepository;
import com.gmsr.scheduler.security.LoginAttemptService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 登录与用户管理。 */
@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final PlayerRepository playerRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    /** 双 token 方案中的 refresh token 管理（存 Redis，7 天滑动） */
    private final com.gmsr.scheduler.security.RefreshTokenService refreshTokenService;
    private final String defaultPassword;
    /** access token 有效期（秒），对应 sa-token.timeout */
    private final long accessTokenSeconds;

    public AuthService(
            AppUserRepository userRepository,
            PlayerRepository playerRepository,
            PasswordEncoder passwordEncoder,
            LoginAttemptService loginAttemptService,
            com.gmsr.scheduler.security.RefreshTokenService refreshTokenService,
            @Value("${gmsr.player.default-password:CHANGE_ME_PLAYER_PASSWORD}") String defaultPassword,
            @Value("${sa-token.timeout:7200}") long accessTokenSeconds) {
        this.userRepository = userRepository;
        this.playerRepository = playerRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
        this.refreshTokenService = refreshTokenService;
        this.defaultPassword = defaultPassword;
        this.accessTokenSeconds = accessTokenSeconds;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request, String clientIp) {
        String username = request.username() == null ? "" : request.username().trim();
        long lockedMinutes = loginAttemptService.lockedMinutes(username, clientIp);
        if (lockedMinutes > 0) {
            throw new BusinessException("登录失败次数过多，请 " + lockedMinutes + " 分钟后再试");
        }
        AppUser user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !user.isEnabled() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptService.recordFailure(username, clientIp);
            // 不区分「用户不存在 / 密码错误 / 账号禁用」，避免暴露账号是否存在
            throw new BusinessException("用户名或密码错误");
        }
        loginAttemptService.recordSuccess(username, clientIp);
        // SA-Token 建立服务端会话：access token 2 小时，刷新由 refresh token 负责
        cn.dev33.satoken.stp.StpUtil.login(user.getId());
        String accessToken = cn.dev33.satoken.stp.StpUtil.getTokenValue();
        String refreshToken = refreshTokenService.issue(user.getId());
        return new LoginResponse(
                accessToken,
                refreshToken,
                saTokenTimeoutSeconds(),
                toView(user),
                user.isMustChangePassword());
    }


    /**
     * 让某账号的登录态立即全部失效。
     * 改密码 / 重置密码 / 禁用账号 / 改动权限 / 删除账号时都要调用 ——
     * 手写 JWT 时代做不到这一点（撤销后旧 token 仍能用满 7 天）。
     */
    private void kickOut(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            cn.dev33.satoken.stp.StpUtil.logout(userId);
        } catch (Exception ignored) {
            // 未登录或 Redis 不可用时忽略
        }
        refreshTokenService.revokeAll(userId);
    }

    /** 用 refresh token 换取新的 access token（无感刷新）。 */
    @Transactional(readOnly = true)
    public LoginResponse refresh(String refreshToken) {
        String[] newRefresh = new String[1];
        Long userId = refreshTokenService.consume(refreshToken, newRefresh);
        if (userId == null) {
            throw new BusinessException("登录已过期，请重新登录");
        }
        AppUser user = userRepository.findById(userId)
                .filter(AppUser::isEnabled)
                .orElseThrow(() -> new BusinessException("账号不可用，请重新登录"));
        cn.dev33.satoken.stp.StpUtil.login(user.getId());
        return new LoginResponse(
                cn.dev33.satoken.stp.StpUtil.getTokenValue(),
                newRefresh[0],
                saTokenTimeoutSeconds(),
                toView(user),
                user.isMustChangePassword());
    }

    /** 登出：同时清掉服务端会话与 refresh token。 */
    public void logout(String refreshToken) {
        try {
            cn.dev33.satoken.stp.StpUtil.logout();
        } catch (Exception ignored) {
            // 未登录状态登出不应报错
        }
        refreshTokenService.revoke(refreshToken);
    }

    /** access token 有效期（秒），与 sa-token.timeout 配置保持一致。 */
    private long saTokenTimeoutSeconds() {
        return accessTokenSeconds;
    }

    @Transactional(readOnly = true)
    public UserView current(Long userId) {
        return userRepository.findById(userId)
                .map(AuthService::toView)
                .orElseThrow(() -> new NotFoundException("用户不存在"));
    }

    @Transactional(readOnly = true)
    public List<UserView> listUsers() {
        return userRepository.findAll().stream().map(AuthService::toView).toList();
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        AppUser user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("用户不存在"));
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException("原密码不正确");
        }
        validatePassword(request.newPassword());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // 改密后强制所有设备重新登录（安全加固）
        kickOut(userId);
        user.setMustChangePassword(false);
        userRepository.save(user);
    }

    /** 管理员把群友密码重置为默认密码，并要求本人下次登录后自行修改。 */
    @Transactional
    public ResetPasswordResponse resetPassword(Long userId) {
        AppUser user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("用户不存在：" + userId));
        user.setPasswordHash(passwordEncoder.encode(defaultPassword));
        // 重置密码后踢掉该账号现有登录态
        kickOut(userId);
        user.setMustChangePassword(true);
        userRepository.save(user);
        return new ResetPasswordResponse(user.getUsername(), "密码已重置，请通知本人尽快登录并设置新密码");
    }

    @Transactional
    public UserView createUser(UserUpsertRequest request) {
        String username = request.username().trim();
        userRepository.findByUsername(username).ifPresent(existing -> {
            throw new BusinessException("用户名已存在：" + username);
        });
        validatePassword(request.password());
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(request.displayName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(parseRole(request.role()));
        user.setEnabled(request.enabled() == null || request.enabled());
        // 群友账号默认给 boss排班 权限（账号管理仍然只有 ADMIN 能做）
        user.setCanSchedule(request.canSchedule() == null || request.canSchedule());
        user.setMustChangePassword(true);
        if (request.playerId() != null) {
            applyPlayer(user, request.playerId());
        } else {
            // 新账号自动建一条群友记录：这样「群友角色一览表」里立刻就有他的位置
            ensurePlayerFor(user);
        }
        userRepository.save(user);
        return toView(user);
    }

    @Transactional
    public UserView updateUser(Long id, UserUpsertRequest request) {
        AppUser user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("用户不存在：" + id));
        user.setDisplayName(request.displayName().trim());
        if (request.role() != null) {
            user.setRole(parseRole(request.role()));
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        if (request.canSchedule() != null) {
            user.setCanSchedule(request.canSchedule());
        }
        // 显示名和绑定的群友名保持一致，避免账号列表和群友表对不上
        if (user.getPlayer() != null && !user.getPlayer().getNickname().equals(user.getDisplayName())) {
            user.getPlayer().setNickname(user.getDisplayName());
            playerRepository.save(user.getPlayer());
        }
        boolean passwordChanged = false;
        if (request.password() != null && !request.password().isBlank()) {
            validatePassword(request.password());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            // 管理员直接改密码 = 一次重置：语义与 createUser / resetPassword 对齐，
            // 本人下次登录必须先自己设新密码，否则管理员知道的那串密码会一直有效
            user.setMustChangePassword(true);
            passwordChanged = true;
        }
        applyPlayer(user, request.playerId());
        userRepository.save(user);
        if (passwordChanged) {
            // 改密码必须吊销已有会话，否则旧 access/refresh token 仍能用
            // （以前只有「重置密码」那条路径做了，编辑接口漏了）
            kickOut(id);
        }
        return toView(user);
    }

    /** 绑定群友：传 null 表示解绑（前端用 -1 表示“不变”的情况在控制器处理）。 */
    private void applyPlayer(AppUser user, Long playerId) {
        if (playerId == null) {
            user.setPlayer(null);
        } else {
            user.setPlayer(playerRepository.findById(playerId)
                    .orElseThrow(() -> new NotFoundException("群友不存在：" + playerId)));
        }
    }

    @Transactional
    public void deleteUser(Long id) {
        AppUser user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("用户不存在：" + id));
        if (user.getRole() == AppUser.Role.ADMIN && userRepository.findAll().stream()
                .filter(u -> u.getRole() == AppUser.Role.ADMIN)
                .count() <= 1) {
            throw new BusinessException("至少保留一个管理员账号");
        }
        userRepository.delete(user);
        kickOut(id);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 6) {
            throw new BusinessException("密码长度至少 6 位");
        }
    }

    /**
     * 角色只有两种：ADMIN（管理员，可管理账号）/ VIEWER（群友）。
     * boss排班 权限由 canSchedule 单独控制，不再用角色区分。
     * 历史数据里的 EDITOR 一律按群友处理。
     */
    private static AppUser.Role parseRole(String role) {
        return "ADMIN".equalsIgnoreCase(role == null ? "" : role.trim())
                ? AppUser.Role.ADMIN
                : AppUser.Role.VIEWER;
    }

    /**
     * 用户改自己的显示名称。
     * 显示名和绑定的群友名是同一个东西（群友表里有唯一约束），所以两边一起改，并且要查重。
     */
    @Transactional
    public UserView updateOwnDisplayName(Long userId, String displayName) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("用户不存在：" + userId));
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            throw new BusinessException("显示名称不能为空");
        }
        if (name.length() > 64) {
            throw new BusinessException("显示名称最长 64 个字");
        }
        if (user.getRole() == AppUser.Role.ADMIN) {
            throw new BusinessException("管理员账号不支持改名，可以改绑定群友的名字");
        }
        if (name.equals(user.getDisplayName())) {
            return toView(user);
        }
        // 账号显示名唯一
        userRepository.findAll().stream()
                .filter(other -> !other.getId().equals(userId) && name.equals(other.getDisplayName()))
                .findFirst()
                .ifPresent(other -> {
                    throw new BusinessException("显示名称「" + name + "」已经被占用了，换一个吧");
                });
        // 群友名唯一
        playerRepository.findByNickname(name).ifPresent(existing -> {
            if (user.getPlayer() == null || !existing.getId().equals(user.getPlayer().getId())) {
                throw new BusinessException("群友「" + name + "」已经存在了，换一个吧");
            }
        });

        user.setDisplayName(name);
        if (user.getPlayer() != null) {
            user.getPlayer().setNickname(name);
            playerRepository.save(user.getPlayer());
        }
        userRepository.save(user);
        return toView(user);
    }

    /** 给账号补一条群友记录并绑定（已存在同名的就直接绑定）。 */
    @Transactional
    public Player ensurePlayerFor(AppUser user) {
        if (user.getPlayer() != null) {
            return user.getPlayer();
        }
        Player player = playerRepository.findByNickname(user.getDisplayName())
                .orElseGet(() -> {
                    Player created = new Player();
                    created.setNickname(user.getDisplayName());
                    created.setQq(user.getUsername());
                    created.setActive(true);
                    created.setSortOrder(nextPlayerSortOrder());
                    return playerRepository.save(created);
                });
        user.setPlayer(player);
        userRepository.save(user);
        return player;
    }

    private int nextPlayerSortOrder() {
        return playerRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .mapToInt(Player::getSortOrder)
                .max()
                .orElse(0) + 1;
    }

    private static UserView toView(AppUser user) {
        var player = user.getPlayer();
        return new UserView(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole().name(),
                player == null ? null : player.getId(),
                player == null ? null : player.getNickname(),
                player == null ? null : player.getQq(),
                user.isMustChangePassword(),
                user.getRole() == AppUser.Role.ADMIN || user.isCanSchedule());
    }
}
