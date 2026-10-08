package io.mediagrid.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import io.mediagrid.auth.user.AdminPasswordReset;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Сквозная проверка службы на настоящем PostgreSQL: вход, обновление, выход, права, управление пользователями. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthFlowTest.Containers.class)
class AuthFlowTest {

    private static final String COOKIE = "mediagrid_refresh";

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18.6-alpine3.24");
        }
    }

    private static final String SERVICE_SECRET = "test-processing-secret-0123456789abcdef";
    private static final String GATEWAY_SECRET = "test-gateway-secret-0123456789abcdef0";

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void adminCreatedFromSettingsCanLogIn() throws Exception {
        MvcResult result = login("ADMIN@test.local", "admin-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains(COOKIE + "=", "HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth");

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(result)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@test.local"));
    }

    @Test
    void wrongPasswordAndUnknownEmailLookTheSame() throws Exception {
        login("admin@test.local", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("nobody@test.local", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void requestsWithoutValidTokenAreRejected() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void userCannotReachAdminFunctions() throws Exception {
        String email = createUser("USER");
        MvcResult userLogin = login(email, "user-password").andExpect(status().isOk()).andReturn();

        mvc.perform(get("/api/auth/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(userLogin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String email = createUser("USER");
        mvc.perform(post("/api/auth/admin/users").header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(email.toUpperCase(), "USER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void refreshRotatesTokenAndReuseRevokesWholeChain() throws Exception {
        String email = createUser("USER");
        Cookie first = login(email, "user-password").andReturn().getResponse().getCookie(COOKIE);

        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        Cookie second = refreshed.getResponse().getCookie(COOKIE);
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        // Старый токен предъявлен повторно — похоже на кражу: отзывается и он, и выданный взамен
        mvc.perform(post("/api/auth/refresh").cookie(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        mvc.perform(post("/api/auth/refresh").cookie(second))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshTokenAndClearsCookie() throws Exception {
        String email = createUser("USER");
        Cookie refresh = login(email, "user-password").andReturn().getResponse().getCookie(COOKIE);

        mvc.perform(post("/api/auth/logout").cookie(refresh))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        containsString("Max-Age=0")));
        mvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserCannotLogInOrRefresh() throws Exception {
        String email = createUser("USER");
        MvcResult userLogin = login(email, "user-password").andReturn();
        Cookie refresh = userLogin.getResponse().getCookie(COOKIE);
        String id = JsonPath.read(userLogin.getResponse().getContentAsString(), "$.user.id");

        mvc.perform(patch("/api/auth/admin/users/" + id).header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        login(email, "user-password")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        mvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lastAdminCannotBeDemoted() throws Exception {
        MvcResult adminLogin = login("admin@test.local", "admin-password").andReturn();
        String id = JsonPath.read(adminLogin.getResponse().getContentAsString(), "$.user.id");

        mvc.perform(patch("/api/auth/admin/users/" + id).header(HttpHeaders.AUTHORIZATION, bearer(adminLogin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"USER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndEndsSessions() throws Exception {
        String email = createUser("USER");
        MvcResult userLogin = login(email, "user-password").andReturn();
        Cookie refresh = userLogin.getResponse().getCookie(COOKIE);

        mvc.perform(put("/api/auth/me/password").header(HttpHeaders.AUTHORIZATION, bearer(userLogin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\": \"wrong\", \"newPassword\": \"new-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));

        mvc.perform(put("/api/auth/me/password").header(HttpHeaders.AUTHORIZATION, bearer(userLogin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\": \"user-password\", \"newPassword\": \"new-password\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/refresh").cookie(refresh)).andExpect(status().isUnauthorized());
        // Токен доступа, выданный до смены пароля, отозван
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(userLogin)))
                .andExpect(status().isUnauthorized());
        login(email, "user-password").andExpect(status().isUnauthorized());
        MvcResult fresh = login(email, "new-password").andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(fresh)))
                .andExpect(status().isOk());
    }

    @Test
    void invalidInputIsReportedInCommonFormat() throws Exception {
        mvc.perform(post("/api/auth/admin/users").header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("not-an-email", "USER").replace("user-password", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void jwksPublishesOnlyPublicKey() throws Exception {
        mvc.perform(get("/api/auth/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void serviceGetsTokenWithServiceRoleOnlyWithCorrectSecret() throws Exception {
        String body = mvc.perform(post("/internal/auth/token")
                        .header(HttpHeaders.AUTHORIZATION, basic("processing-service", SERVICE_SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn().getResponse().getContentAsString();
        Jwt token = jwtDecoder.decode(JsonPath.read(body, "$.accessToken"));
        assertThat(token.getSubject()).isEqualTo("processing-service");
        assertThat(token.getClaimAsStringList("roles")).containsExactly("SERVICE");

        // Токен службы не открывает пользовательские пути
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token.getTokenValue()))
                .andExpect(status().isForbidden());

        for (String wrong : new String[] {basic("processing-service", SERVICE_SECRET + "x"),
                basic("unknown-service", SERVICE_SECRET), "Basic not-base64!", "Bearer " + token.getTokenValue()}) {
            mvc.perform(post("/internal/auth/token").header(HttpHeaders.AUTHORIZATION, wrong))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CLIENT"));
        }
        mvc.perform(post("/internal/auth/token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forgottenAdminPasswordIsResetOnServerAndSessionsEnd() throws Exception {
        String email = createUser("ADMIN");
        MvcResult oldLogin = login(email, "user-password").andReturn();
        Cookie session = oldLogin.getResponse().getCookie(COOKIE);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(AdminPasswordReset.reset(connection, email.toUpperCase(), "new-admin-password", passwordEncoder))
                    .isEqualTo(email);
        }

        login(email, "user-password").andExpect(status().isUnauthorized());
        login(email, "new-admin-password").andExpect(status().isOk());
        // Сеансы, открытые со старым паролем, завершены, их токены доступа отозваны
        mvc.perform(post("/api/auth/refresh").cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(oldLogin)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessTokenCarriesSessionAndGeneration() throws Exception {
        MvcResult first = login(createUser("USER"), "user-password").andReturn();
        Jwt token = jwtDecoder.decode(JsonPath.read(first.getResponse().getContentAsString(), "$.accessToken"));
        assertThat(UUID.fromString(token.getClaimAsString("sid"))).isNotNull();
        assertThat(((Number) token.getClaim("ver")).intValue()).isZero();

        // Обновление продолжает тот же вход
        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(first.getResponse().getCookie(COOKIE)))
                .andExpect(status().isOk()).andReturn();
        Jwt next = jwtDecoder.decode(JsonPath.read(refreshed.getResponse().getContentAsString(), "$.accessToken"));
        assertThat(next.getClaimAsString("sid")).isEqualTo(token.getClaimAsString("sid"));
    }

    @Test
    void logoutRevokesAccessTokenOfThatSessionOnly() throws Exception {
        String email = createUser("USER");
        MvcResult phone = login(email, "user-password").andReturn();
        MvcResult laptop = login(email, "user-password").andReturn();

        mvc.perform(post("/api/auth/logout").cookie(phone.getResponse().getCookie(COOKIE)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(phone)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(laptop)))
                .andExpect(status().isOk());

        // Шлюз узнаёт об отзыве из списка
        // Токен уже отозван — проверяющий декодер его не примет, утверждения читаются без проверки
        String sid = SignedJWT.parse(accessToken(phone)).getJWTClaimsSet().getStringClaim("sid");
        String userId = JsonPath.read(phone.getResponse().getContentAsString(), "$.user.id");
        List<Map<String, Object>> entries = JsonPath.read(revocations(), "$.revocations[?(@.sessionId == '" + sid + "')]");
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry).containsEntry("userId", userId).containsEntry("minVersion", null);
            assertThat(entry.get("expiresAt")).isNotNull();
        });
        // Повторный выход тем же куки новой записи не добавляет
        mvc.perform(post("/api/auth/logout").cookie(phone.getResponse().getCookie(COOKIE)));
        assertThat((List<?>) JsonPath.read(revocations(), "$.revocations[?(@.sessionId == '" + sid + "')]"))
                .hasSize(1);
    }

    @Test
    void roleChangeRevokesAccessTokensButKeepsSession() throws Exception {
        String email = createUser("USER");
        MvcResult userLogin = login(email, "user-password").andReturn();
        String id = JsonPath.read(userLogin.getResponse().getContentAsString(), "$.user.id");

        mvc.perform(patch("/api/auth/admin/users/" + id).header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"ADMIN\"}"))
                .andExpect(status().isOk());

        // В старом токене прежняя роль — он отозван; обновление выдаёт токен с новой ролью
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(userLogin)))
                .andExpect(status().isUnauthorized());
        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(userLogin.getResponse().getCookie(COOKIE)))
                .andExpect(status().isOk()).andReturn();
        Jwt token = jwtDecoder.decode(accessToken(refreshed));
        assertThat(token.getClaimAsStringList("roles")).containsExactly("ADMIN");
        assertThat(((Number) token.getClaim("ver")).intValue()).isEqualTo(1);
        mvc.perform(get("/api/auth/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(refreshed)))
                .andExpect(status().isOk());
        List<Integer> versions = JsonPath.read(revocations(), "$.revocations[?(@.userId == '" + id + "')].minVersion");
        assertThat(versions).containsExactly(1);

        // Смена только имени токены не трогает
        mvc.perform(patch("/api/auth/admin/users/" + id).header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"Другое имя\", \"role\": \"ADMIN\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(refreshed)))
                .andExpect(status().isOk());
    }

    @Test
    void disablingUserRevokesAccessTokens() throws Exception {
        String email = createUser("USER");
        MvcResult userLogin = login(email, "user-password").andReturn();
        String id = JsonPath.read(userLogin.getResponse().getContentAsString(), "$.user.id");

        mvc.perform(patch("/api/auth/admin/users/" + id).header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(userLogin)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revocationListIsOnlyForServices() throws Exception {
        mvc.perform(get("/internal/auth/revocations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/auth/revocations").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/auth/revocations").header(HttpHeaders.AUTHORIZATION, serviceBearer("gateway",
                        GATEWAY_SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revocations").isArray());
    }

    @Test
    void passwordResetOnServerIsOnlyForAdmins() throws Exception {
        String user = createUser("USER");
        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> AdminPasswordReset.reset(connection, user, "new-user-password", passwordEncoder))
                    .hasMessageContaining("не администратор");
            assertThatThrownBy(() -> AdminPasswordReset.reset(connection, "nobody@test.local", "new-password-1",
                    passwordEncoder))
                    .hasMessageContaining("учётной записи nobody@test.local нет");
            assertThatThrownBy(() -> AdminPasswordReset.reset(connection, "admin@test.local", "short", passwordEncoder))
                    .hasMessageContaining("от 8 до 64");
        }
        // Отказ ничего не изменил
        login(user, "user-password").andExpect(status().isOk());
        login("admin@test.local", "admin-password").andExpect(status().isOk());
    }

    private String revocations() throws Exception {
        return mvc.perform(get("/internal/auth/revocations")
                        .header(HttpHeaders.AUTHORIZATION, serviceBearer("gateway", GATEWAY_SECRET)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String serviceBearer(String name, String secret) throws Exception {
        String body = mvc.perform(post("/internal/auth/token").header(HttpHeaders.AUTHORIZATION, basic(name, secret)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    private static String accessToken(MvcResult loginResult) throws Exception {
        return JsonPath.read(loginResult.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String basic(String name, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((name + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}"));
    }

    private String adminBearer() throws Exception {
        return bearer(login("admin@test.local", "admin-password").andExpect(status().isOk()).andReturn());
    }

    /** Создаёт пользователя с паролем user-password и возвращает его почту. */
    private String createUser(String role) throws Exception {
        String email = "user-" + UUID.randomUUID() + "@test.local";
        mvc.perform(post("/api/auth/admin/users").header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(email, role)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email));
        return email;
    }

    private static String userJson(String email, String role) {
        return "{\"email\": \"" + email + "\", \"password\": \"user-password\", \"displayName\": \"Тест\", "
                + "\"role\": \"" + role + "\"}";
    }

    private static String bearer(MvcResult loginResult) throws Exception {
        return "Bearer " + JsonPath.read(loginResult.getResponse().getContentAsString(), "$.accessToken");
    }
}
