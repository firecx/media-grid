package io.mediagrid.auth.web;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import io.mediagrid.auth.config.AuthProperties;
import io.mediagrid.auth.key.SigningKeyService;
import io.mediagrid.auth.token.AccessTokenService;
import io.mediagrid.auth.token.SessionService;
import io.mediagrid.auth.token.SessionService.Session;
import io.mediagrid.auth.user.UserDtos.ChangePasswordRequest;
import io.mediagrid.auth.user.UserDtos.UserResponse;
import io.mediagrid.auth.user.UserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

/**
 * Вход, обновление, выход и действия пользователя со своей учётной записью.
 * Токен доступа отдаётся в теле ответа, обновляемый — только в куки HttpOnly,
 * недоступной скриптам страницы и отправляемой браузером только на /api/auth.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String COOKIE_PATH = "/api/auth";

    private final SessionService sessions;
    private final UserService users;
    private final AccessTokenService accessTokens;
    private final SigningKeyService keys;
    private final AuthProperties.Cookie cookie;
    private final Duration refreshTokenTtl;

    public AuthController(SessionService sessions, UserService users, AccessTokenService accessTokens,
                          SigningKeyService keys, AuthProperties properties) {
        this.sessions = sessions;
        this.users = users;
        this.accessTokens = accessTokens;
        this.keys = keys;
        this.cookie = properties.cookie();
        this.refreshTokenTtl = properties.refreshTokenTtl();
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return withSession(sessions.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        String refreshToken = readRefreshCookie(request);
        if (refreshToken == null) {
            throw ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Сеанс истёк, войдите заново");
        }
        return withSession(sessions.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String refreshToken = readRefreshCookie(request);
        if (refreshToken != null) {
            sessions.logout(refreshToken);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO)).build();
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.of(users.get(UUID.fromString(jwt.getSubject())));
    }

    /** После смены пароля все входы завершаются, в том числе текущий: нужно войти заново. */
    @PutMapping("/me/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        users.changePassword(UUID.fromString(jwt.getSubject()), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO)).build();
    }

    /** Открытые ключи для проверки токенов доступа в других службах. */
    @GetMapping("/jwks")
    public Map<String, Object> jwks() {
        return keys.publicJwkSet().toJSONObject();
    }

    private ResponseEntity<TokenResponse> withSession(Session session) {
        TokenResponse body = new TokenResponse(session.accessToken(), "Bearer", accessTokens.ttl().toSeconds(),
                UserResponse.of(session.user()));
        String setCookie = refreshCookie(session.refreshToken().value(), refreshTokenTtl);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, setCookie).body(body);
    }

    private String readRefreshCookie(HttpServletRequest request) {
        Cookie found = WebUtils.getCookie(request, cookie.name());
        return found == null || found.getValue().isBlank() ? null : found.getValue();
    }

    private String refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(cookie.name(), value)
                .httpOnly(true)
                .secure(cookie.secure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
    }
}
