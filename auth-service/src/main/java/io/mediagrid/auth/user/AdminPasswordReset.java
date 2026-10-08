package io.mediagrid.auth.user;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import io.mediagrid.auth.config.SecurityConfig;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Восстановление доступа, если администратор забыл пароль, а другого администратора нет. Запускается на
 * сервере, в контейнере службы авторизации (значит, нужен доступ к серверу, а не к сайту):
 * <pre>
 *   docker compose exec auth-service reset-admin-password admin@example.com
 * </pre>
 * Новый пароль спрашивается дважды и на экран не выводится; без терминала — одна строка со стандартного
 * ввода. Учётная запись включается, все её сеансы завершаются, выданные токены доступа отзываются. Работает
 * только для администраторов: пароли пользователей сбрасывает администратор в интерфейсе.
 * <p>
 * Отдельная программа без Spring: не запускает вторую копию службы, к базе подключается учётной записью
 * службы, пароль хеширует тем же способом, что и служба.
 */
public final class AdminPasswordReset {

    /**
     * Сколько хранить запись об отзыве токенов. Срок токена доступа задан в настройках службы (15 минут),
     * программе он неизвестен — с запасом; лишняя запись ничему не мешает и удаляется службой.
     */
    private static final Duration KEEP_REVOCATION = Duration.ofDays(1);

    private AdminPasswordReset() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Использование: reset-admin-password <почта администратора>");
            System.exit(2);
        }
        try {
            String password = readNewPassword();
            String url = "jdbc:postgresql://" + env("POSTGRES_HOST", "localhost") + ":" + env("POSTGRES_PORT", "5432")
                    + "/" + env("POSTGRES_DB", "mediagrid");
            try (Connection connection = DriverManager.getConnection(url, env("DB_USER", "mediagrid_auth"),
                    env("DB_PASSWORD", ""))) {
                String email = reset(connection, args[0], password, SecurityConfig.createPasswordEncoder());
                System.out.println("Пароль администратора " + email + " изменён, все его сеансы завершены, "
                        + "выданные токены доступа отозваны.");
            }
        } catch (ResetException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        } catch (SQLException | IOException e) {
            System.err.println("Ошибка: нет связи с базой данных или чтения ввода: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Задаёт администратору новый пароль, включает учётную запись, завершает её сеансы и отзывает токены
     * доступа (новое поколение, как AccessRevocationService.revokeAll) — одной транзакцией.
     *
     * @return почта администратора в том виде, в каком она хранится
     */
    public static String reset(Connection connection, String email, String newPassword, PasswordEncoder encoder)
            throws SQLException {
        if (newPassword.length() < UserDtos.PASSWORD_MIN || newPassword.length() > UserDtos.PASSWORD_MAX) {
            throw new ResetException("пароль должен быть от " + UserDtos.PASSWORD_MIN + " до "
                    + UserDtos.PASSWORD_MAX + " символов");
        }
        String normalized = UserService.normalizeEmail(email);
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            UUID id;
            try (PreparedStatement find = connection.prepareStatement(
                    "select id, role from auth.users where email = ? for update")) {
                find.setString(1, normalized);
                try (ResultSet row = find.executeQuery()) {
                    if (!row.next()) {
                        throw new ResetException("учётной записи " + normalized + " нет. Администраторы: "
                                + admins(connection));
                    }
                    if (!Role.ADMIN.name().equals(row.getString("role"))) {
                        throw new ResetException(normalized + " — не администратор. Пароль пользователя сбрасывает "
                                + "администратор в интерфейсе. Администраторы: " + admins(connection));
                    }
                    id = row.getObject("id", UUID.class);
                }
            }
            int version;
            try (PreparedStatement update = connection.prepareStatement("""
                    update auth.users set password_hash = ?, enabled = true, updated_at = now(),
                        token_version = token_version + 1
                    where id = ? returning token_version""")) {
                update.setString(1, encoder.encode(newPassword));
                update.setObject(2, id);
                try (ResultSet row = update.executeQuery()) {
                    row.next();
                    version = row.getInt(1);
                }
            }
            try (PreparedStatement revokeAccess = connection.prepareStatement("""
                    insert into auth.access_revocations (id, user_id, min_version, expires_at, created_at)
                    values (gen_random_uuid(), ?, ?, now() + make_interval(secs => ?), now())""")) {
                revokeAccess.setObject(1, id);
                revokeAccess.setInt(2, version);
                revokeAccess.setDouble(3, KEEP_REVOCATION.toSeconds());
                revokeAccess.executeUpdate();
            }
            try (PreparedStatement revoke = connection.prepareStatement(
                    "update auth.refresh_tokens set revoked_at = now() where user_id = ? and revoked_at is null")) {
                revoke.setObject(1, id);
                revoke.executeUpdate();
            }
            connection.commit();
            return normalized;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static String admins(Connection connection) throws SQLException {
        List<String> emails = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "select email from auth.users where role = 'ADMIN' order by email");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                emails.add(rows.getString(1));
            }
        }
        return emails.isEmpty() ? "нет" : String.join(", ", emails);
    }

    private static String readNewPassword() throws IOException {
        Console console = System.console();
        if (console == null) {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) {
                throw new ResetException("не передан новый пароль");
            }
            return line;
        }
        char[] first = console.readPassword("Новый пароль: ");
        char[] second = console.readPassword("Ещё раз: ");
        if (first == null || second == null || !Arrays.equals(first, second)) {
            throw new ResetException("пароли не совпадают");
        }
        return new String(first);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Понятная администратору причина отказа — без трассировки стека. */
    static final class ResetException extends RuntimeException {
        ResetException(String message) {
            super(message);
        }
    }
}
