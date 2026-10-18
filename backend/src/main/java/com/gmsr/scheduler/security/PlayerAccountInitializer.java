package com.gmsr.scheduler.security;

import com.gmsr.scheduler.domain.AppUser;
import com.gmsr.scheduler.domain.Player;
import com.gmsr.scheduler.repository.AppUserRepository;
import com.gmsr.scheduler.repository.PlayerRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 为还没有账号的群友自动创建只读账号，并绑定到对应群友。
 * 账号名 = 群友昵称，初始密码见 gmsr.player.default-password（默认 CHANGE_ME_ADMIN_PASSWORD）。
 *
 * 已存在的账号不会被覆盖，只会补上「绑定群友」这一项，方便管理员改过密码后继续使用。
 */
@Component
@Order(3)
public class PlayerAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlayerAccountInitializer.class);

    private final AppUserRepository userRepository;
    private final PlayerRepository playerRepository;
    private final PasswordEncoder passwordEncoder;
    private final String defaultPassword;

    public PlayerAccountInitializer(
            AppUserRepository userRepository,
            PlayerRepository playerRepository,
            PasswordEncoder passwordEncoder,
            @Value("${gmsr.player.default-password:CHANGE_ME_PLAYER_PASSWORD}") String defaultPassword) {
        this.userRepository = userRepository;
        this.playerRepository = playerRepository;
        this.passwordEncoder = passwordEncoder;
        this.defaultPassword = defaultPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<String> created = new ArrayList<>();
        for (Player player : playerRepository.findAllByOrderBySortOrderAscIdAsc()) {
            // 已经有账号绑定到这位群友（按 QQ 号登录）就跳过
            boolean hasAccount = userRepository.findAll().stream()
                    .anyMatch(u -> u.getPlayer() != null && u.getPlayer().getId().equals(player.getId()));
            if (hasAccount) {
                continue;
            }
            String username = player.getQq() != null && !player.getQq().isBlank()
                    ? player.getQq()
                    : player.getNickname();
            AppUser existing = userRepository.findByUsername(username).orElse(null);
            if (existing != null) {
                existing.setPlayer(player);
                userRepository.save(existing);
                log.info("账号 {} 已存在，已补上绑定群友 {}", existing.getUsername(), player.getNickname());
                continue;
            }
            AppUser user = new AppUser();
            user.setUsername(username);
            user.setDisplayName(player.getNickname());
            user.setPasswordHash(passwordEncoder.encode(defaultPassword));
            user.setRole(AppUser.Role.VIEWER);
            user.setEnabled(true);
            user.setMustChangePassword(true);
            user.setPlayer(player);
            userRepository.save(user);
            created.add(player.getNickname());
        }
        if (!created.isEmpty()) {
            log.info("已为 {} 位群友创建账号（初始密码 {}）：{}", created.size(), defaultPassword, String.join("、", created));
        }
    }
}
