package io.mediagrid.auth.user;

import java.util.Locale;
import java.util.UUID;

import io.mediagrid.auth.token.RefreshTokenService;
import io.mediagrid.auth.user.UserDtos.CreateUserRequest;
import io.mediagrid.auth.user.UserDtos.UpdateUserRequest;
import io.mediagrid.support.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, RefreshTokenService refreshTokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Пользователь не найден"));
    }

    @Transactional(readOnly = true)
    public Page<User> list(Pageable pageable) {
        return users.findAll(pageable);
    }

    @Transactional
    public User create(CreateUserRequest request) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "Пользователь с такой почтой уже есть");
        }
        User user = new User(email, passwordEncoder.encode(request.password()), request.displayName().trim(),
                request.role());
        return users.save(user);
    }

    @Transactional
    public User update(UUID id, UpdateUserRequest request) {
        User user = get(id);
        boolean losesAdmin = user.getRole() == Role.ADMIN && user.isEnabled()
                && ((request.role() != null && request.role() != Role.ADMIN)
                    || Boolean.FALSE.equals(request.enabled()));
        if (losesAdmin && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw ApiException.conflict("LAST_ADMIN", "Нельзя отключить или понизить последнего администратора");
        }
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName().trim());
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
            if (!request.enabled()) {
                refreshTokens.revokeAllForUser(user.getId());
            }
        }
        return user;
    }

    /** Сброс пароля администратором: все входы пользователя завершаются. */
    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        User user = get(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        refreshTokens.revokeAllForUser(user.getId());
    }

    /** Смена собственного пароля: все входы пользователя завершаются. */
    @Transactional
    public void changePassword(UUID id, String currentPassword, String newPassword) {
        User user = get(id);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "Текущий пароль указан неверно");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        refreshTokens.revokeAllForUser(user.getId());
    }
}
