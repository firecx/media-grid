package io.mediagrid.auth.token;

import java.util.UUID;

import io.mediagrid.auth.token.RefreshTokenService.IssuedRefreshToken;
import io.mediagrid.auth.token.RefreshTokenService.Rotation;
import io.mediagrid.auth.user.User;
import io.mediagrid.auth.user.UserRepository;
import io.mediagrid.auth.user.UserService;
import io.mediagrid.auth.web.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Вход, обновление и выход: связывает проверку пароля с выдачей пары токенов. */
@Service
public class SessionService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;

    /** Отпечаток пароля для сравнения, когда пользователь не найден: время ответа не выдаёт, есть ли такая почта. */
    private final String dummyHash;

    public SessionService(UserRepository users, PasswordEncoder passwordEncoder, AccessTokenService accessTokens,
                          RefreshTokenService refreshTokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Session login(String email, String password) {
        User user = users.findByEmail(UserService.normalizeEmail(email)).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (!user.isEnabled()) {
            throw ApiException.forbidden("ACCOUNT_DISABLED", "Учётная запись отключена");
        }
        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.setPasswordHash(passwordEncoder.encode(password));
        }
        return new Session(user, accessTokens.issue(user), refreshTokens.issue(user));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String refreshToken) {
        Rotation rotation = refreshTokens.rotate(refreshToken);
        return new Session(rotation.user(), accessTokens.issue(rotation.user()), rotation.token());
    }

    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("INVALID_CREDENTIALS", "Неверная почта или пароль");
    }

    public record Session(User user, String accessToken, IssuedRefreshToken refreshToken) {
    }
}
