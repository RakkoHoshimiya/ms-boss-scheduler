package com.gmsr.scheduler.security;

import com.gmsr.scheduler.domain.AppUser;
import com.gmsr.scheduler.repository.AppUserRepository;
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
 * 首次启动且系统中没有任何用户时，按配置创建默认管理员账号。
 * 用户名默认 admin，密码默认 CHANGE_ME_ADMIN_PASSWORD，可用环境变量 GMSR_ADMIN_USER / GMSR_ADMIN_PASSWORD 覆盖。
 */
@Component
@Order(4)
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;

    public AdminAccountInitializer(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${gmsr.admin.username:admin}") String adminUsername,
            @Value("${gmsr.admin.password:CHANGE_ME_ADMIN_PASSWORD}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        AppUser admin = new AppUser();
        admin.setUsername(adminUsername);
        admin.setDisplayName("管理员");
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setRole(AppUser.Role.ADMIN);
        admin.setEnabled(true);
        admin.setMustChangePassword(false);
        userRepository.save(admin);
        log.info("已创建默认管理员账号：{}（请登录后立即修改密码）", adminUsername);
    }
}
