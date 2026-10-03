package io.mediagrid.auth.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import io.mediagrid.auth.token.AccessTokenService;
import io.mediagrid.auth.token.ServiceClients;
import io.mediagrid.support.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Токены для обращений служб друг к другу (например, служба обработки читает файлы из службы хранения).
 * Путь /internal/** шлюз наружу не пропускает; служба предъявляет своё имя и секрет в заголовке
 * Authorization: Basic, как в схеме client_credentials OAuth 2.
 */
@RestController
@RequestMapping("/internal/auth")
public class ServiceTokenController {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenController.class);
    private static final String BASIC = "Basic ";

    private final ServiceClients clients;
    private final AccessTokenService accessTokens;

    public ServiceTokenController(ServiceClients clients, AccessTokenService accessTokens) {
        this.clients = clients;
        this.accessTokens = accessTokens;
    }

    @PostMapping("/token")
    public ServiceTokenResponse token(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                      String authorization) {
        String[] credentials = parseBasic(authorization);
        if (credentials == null || !clients.isValid(credentials[0], credentials[1])) {
            log.warn("Отказ в токене службы: неверное имя или секрет");
            throw ApiException.unauthorized("INVALID_CLIENT", "Неверное имя службы или секрет");
        }
        return new ServiceTokenResponse(accessTokens.issueForService(credentials[0]), "Bearer",
                accessTokens.ttl().toSeconds());
    }

    /** Имя и секрет из «Basic base64(имя:секрет)»; null, если заголовок не такой. */
    private static String[] parseBasic(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BASIC, 0, BASIC.length())) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(BASIC.length()).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int colon = decoded.indexOf(':');
        return colon > 0 ? new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)} : null;
    }

    public record ServiceTokenResponse(String accessToken, String tokenType, long expiresIn) {
    }
}
