package io.mediagrid.auth.user;

import io.mediagrid.auth.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Создаёт первого администратора из ADMIN_EMAIL и ADMIN_PASSWORD, если администраторов в базе нет.
 * Когда администратор уже есть, переменные ни на что не влияют.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties.BootstrapAdmin settings;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, AuthProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.settings = properties.bootstrapAdmin();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByRole(Role.ADMIN)) {
            return;
        }
        if (!StringUtils.hasText(settings.email()) || !StringUtils.hasText(settings.password())) {
            log.warn("Администраторов нет, а ADMIN_EMAIL и ADMIN_PASSWORD не заданы: войти в систему будет некому");
            return;
        }
        if (settings.password().length() < UserDtos.PASSWORD_MIN) {
            log.warn("ADMIN_PASSWORD короче {} символов, администратор не создан", UserDtos.PASSWORD_MIN);
            return;
        }
        String email = UserService.normalizeEmail(settings.email());
        User admin = users.findByEmail(email)
                .orElseGet(() -> new User(email, passwordEncoder.encode(settings.password()), "Администратор",
                        Role.ADMIN));
        admin.setRole(Role.ADMIN);
        admin.setEnabled(true);
        users.save(admin);
        log.info("Создан первый администратор {}", email);
    }
}
