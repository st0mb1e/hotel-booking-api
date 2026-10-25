package ru.hotelbooking.common;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.hotelbooking.common.api.*;
import ru.hotelbooking.common.security.JwtSupport;
import ru.hotelbooking.common.web.RequestIds;

class CoreRulesTest {
    @Test
    void dateRangeRejectsEmptyReversedAndExcessiveStays() {
        var day = LocalDate.of(2027, 1, 1);
        assertThatThrownBy(() -> Dates.requireRange(day, day)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Dates.requireRange(day, day.minusDays(1)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Dates.requireRange(day, day.plusYears(1).plusDays(1)))
                .isInstanceOf(ApiException.class);
        assertThatCode(() -> Dates.requireRange(day, day.plusDays(1))).doesNotThrowAnyException();
    }

    @Test
    void requestIdsCannotInjectLogLines() {
        assertThat(RequestIds.normalize("request-123")).isEqualTo("request-123");
        assertThat(RequestIds.normalize("bad\nid")).matches("[a-f0-9-]{36}");
        assertThat(RequestIds.normalize(null)).isNotBlank();
    }

    @Test
    void userTokensCannotAcquireInternalAuthorities() {
        var jwt =
                Jwt.withTokenValue("test")
                        .header("alg", "RS256")
                        .subject("1")
                        .audience(List.of(JwtSupport.USER_AUDIENCE))
                        .claim("roles", List.of("ADMIN", "SUPERUSER"))
                        .claim("scope", "rooms:write")
                        .build();
        assertThat(JwtSupport.authorities(jwt))
                .extracting(Object::toString)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void serviceTokenRequiresExpectedSubject() {
        var jwt =
                Jwt.withTokenValue("test")
                        .header("alg", "RS256")
                        .subject("somebody")
                        .audience(List.of(JwtSupport.SERVICE_AUDIENCE))
                        .claim("scope", "rooms:write")
                        .build();
        assertThat(JwtSupport.authorities(jwt)).isEmpty();
    }
}
