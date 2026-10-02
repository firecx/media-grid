package io.mediagrid.auth.user;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Форматы запросов и ответов для работы с пользователями. */
public final class UserDtos {

    // Ограничение сверху: BCrypt учитывает не больше 72 байт пароля
    static final int PASSWORD_MIN = 8;
    static final int PASSWORD_MAX = 64;

    private UserDtos() {
    }

    public record UserResponse(UUID id, String email, String displayName, Role role, boolean enabled,
                               Instant createdAt) {

        public static UserResponse of(User user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                    user.isEnabled(), user.getCreatedAt());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = PASSWORD_MIN, max = PASSWORD_MAX) String password,
            @NotBlank @Size(max = 100) String displayName,
            @NotNull Role role) {
    }

    /** Изменение пользователя администратором; незаполненные поля не меняются. */
    public record UpdateUserRequest(
            @Size(min = 1, max = 100) String displayName,
            Role role,
            Boolean enabled) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = PASSWORD_MIN, max = PASSWORD_MAX) String newPassword) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = PASSWORD_MIN, max = PASSWORD_MAX) String newPassword) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
    }
}
