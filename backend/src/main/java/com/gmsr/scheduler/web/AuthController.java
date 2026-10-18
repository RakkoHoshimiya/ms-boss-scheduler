package com.gmsr.scheduler.web;

import com.gmsr.scheduler.dto.ApiDtos.ChangePasswordRequest;
import com.gmsr.scheduler.dto.ApiDtos.LoginRequest;
import com.gmsr.scheduler.dto.ApiDtos.LoginResponse;
import com.gmsr.scheduler.dto.ApiDtos.UserUpsertRequest;
import com.gmsr.scheduler.dto.ApiDtos.ResetPasswordResponse;
import com.gmsr.scheduler.dto.ApiDtos.UserView;
import com.gmsr.scheduler.security.CurrentUser;
import com.gmsr.scheduler.service.AuthService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        return authService.login(request, clientIp(httpRequest));
    }

    /**
     * 无感刷新：用 refresh token 换新的 access token。
     * 前端在收到 401 时自动调用，成功后重试原请求，用户无感知。
     * 7 天完全没使用则 refresh token 也过期，此时返回 400，前端引导重新登录。
     */
    @PostMapping("/auth/refresh")
    public LoginResponse refresh(@RequestBody java.util.Map<String, String> body) {
        return authService.refresh(body == null ? null : body.get("refreshToken"));
    }

    /** 登出：清掉服务端会话与 refresh token。 */
    @PostMapping("/auth/logout")
    public java.util.Map<String, Object> logout(@RequestBody(required = false) java.util.Map<String, String> body) {
        authService.logout(body == null ? null : body.get("refreshToken"));
        return java.util.Map.of("ok", true);
    }

    /** 反向代理后面取真实来源 IP。 */
    private static String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        for (String header : new String[] {"X-Real-IP", "X-Forwarded-For"}) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                return (comma > 0 ? value.substring(0, comma) : value).trim();
            }
        }
        return request.getRemoteAddr();
    }

    @GetMapping("/auth/me")
    public UserView me() {
        Long userId = CurrentUser.id();
        if (userId == null) {
            throw new com.gmsr.scheduler.service.BusinessException("请先登录");
        }
        return authService.current(userId);
    }

    @PutMapping("/auth/display-name")
    public UserView updateDisplayName(
            @jakarta.validation.Valid @RequestBody com.gmsr.scheduler.dto.ApiDtos.DisplayNameRequest request) {
        Long userId = CurrentUser.id();
        if (userId == null) {
            throw new com.gmsr.scheduler.service.BusinessException("请先登录");
        }
        return authService.updateOwnDisplayName(userId, request.displayName());
    }

    @PostMapping("/auth/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(CurrentUser.id(), request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/users")
    public List<UserView> users() {
        return authService.listUsers();
    }

    @PostMapping("/admin/users")
    public UserView createUser(@Valid @RequestBody UserUpsertRequest request) {
        return authService.createUser(request);
    }

    @PutMapping("/admin/users/{id}")
    public UserView updateUser(@PathVariable Long id, @Valid @RequestBody UserUpsertRequest request) {
        return authService.updateUser(id, request);
    }

    /** 管理员把群友密码重置为默认密码，本人下次登录后自行修改。 */
    @PostMapping("/admin/users/{id}/reset-password")
    public ResetPasswordResponse resetPassword(@PathVariable Long id) {
        return authService.resetPassword(id);
    }

    @DeleteMapping("/admin/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        authService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
