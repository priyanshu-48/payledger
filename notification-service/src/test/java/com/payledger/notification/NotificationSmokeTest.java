package com.payledger.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationSmokeTest extends AbstractPostgresTest {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void healthIsUpAndFlywayCreatedTheMailbox() {
        assertThat(http.getForObject("/actuator/health", String.class)).contains("\"status\":\"UP\"");
        assertThat(jdbc.queryForObject("select count(*) from notifications", Long.class)).isNotNull();
    }
}
