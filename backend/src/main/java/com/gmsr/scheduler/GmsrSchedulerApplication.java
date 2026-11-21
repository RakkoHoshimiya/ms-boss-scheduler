package com.gmsr.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * GMSR 国际服冒险岛 BOSS 组队排班系统 入口。
 *
 * <p>排除 {@link UserDetailsServiceAutoConfiguration}：本项目用 SA-Token 做鉴权，
 * 完全不使用 Spring Security 的 UserDetailsService，但 starter-security 在发现
 * 容器里没有 UserDetailsService 时会自动建一个 `user` 账号，并把随机生成的
 * 口令 <b>以 WARN 级别打进日志</b>：
 *
 * <pre>Using generated security password: c788e392-4b5e-461f-b601-046930106b1a</pre>
 *
 * <p>这个口令本身没有用处（安全过滤链没开 httpBasic/formLogin，登录走 /api/auth/login），
 * 但它会随日志落盘保留 14 天，等于在服务器上长期留一份明文凭据，属于纯负资产。
 * 排除该自动配置后不再有 `user` 账号，也不再打印这行日志。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class GmsrSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GmsrSchedulerApplication.class, args);
    }
}
