package io.mediagrid.auth.web;

import java.net.URI;
import java.util.UUID;

import io.mediagrid.auth.user.User;
import io.mediagrid.auth.user.UserDtos.CreateUserRequest;
import io.mediagrid.auth.user.UserDtos.PageResponse;
import io.mediagrid.auth.user.UserDtos.ResetPasswordRequest;
import io.mediagrid.auth.user.UserDtos.UpdateUserRequest;
import io.mediagrid.auth.user.UserDtos.UserResponse;
import io.mediagrid.auth.user.UserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Управление учётными записями — только для роли ADMIN (проверяется в SecurityConfig по пути).
 * Удаления нет: пользователь отключается, его файлы остаются за ним.
 */
@RestController
@RequestMapping("/api/auth/admin/users")
public class AdminUserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserService users;

    public AdminUserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public PageResponse<UserResponse> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by("createdAt"));
        Page<User> result = users.list(request);
        return new PageResponse<>(result.map(UserResponse::of).getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements());
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        User user = users.create(request);
        return ResponseEntity.created(URI.create("/api/auth/admin/users/" + user.getId()))
                .body(UserResponse.of(user));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return UserResponse.of(users.get(id));
    }

    @PatchMapping("/{id}")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return UserResponse.of(users.update(id, request));
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request) {
        users.resetPassword(id, request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
