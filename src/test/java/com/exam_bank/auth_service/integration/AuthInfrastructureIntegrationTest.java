package com.exam_bank.auth_service.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.exam_bank.auth_service.service.SubscriptionRequestService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.main.lazy-initialization=true",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.task.scheduling.enabled=false",
        "auth.jwt.issuer=test_issuer",
        "auth.jwt.expiration-seconds=3600",
        "auth.jwt.secret=VjNyeVNlY3VyZVNlY3JldEtleUZvckF1dGhTZXJ2aWNlMTIzNDU2Nzg5MDE=",
        "auth.refresh-token.expiration-seconds=604800",
        "auth.user-profile-cache.ttl-seconds=7200",
        "auth.forgot-password.otp-ttl-seconds=300",
        "auth.email-verification.otp-ttl-seconds=300",
        "auth.forgot-password.rate-limit.forgot-max-attempts=5",
        "auth.forgot-password.rate-limit.forgot-window-seconds=300",
        "auth.forgot-password.rate-limit.verify-max-attempts=10",
        "auth.forgot-password.rate-limit.verify-window-seconds=300",
        "auth.forgot-password.rate-limit.resend-max-attempts=3",
        "auth.forgot-password.rate-limit.resend-window-seconds=300",
        "app.cors.allowed-origins=http://localhost:5173",
        "minio.url=http://localhost:9000",
        "minio.access-key=test-access",
        "minio.secret-key=test-secret",
        "minio.bucket-name=test-bucket"
})
@DisplayName("Auth Infrastructure Integration Test")
@SuppressWarnings("resource")
class AuthInfrastructureIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("auth_service_it")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private SubscriptionRequestService subscriptionRequestService;

    @Test
    @DisplayName("connects to Postgres, Redis, and RabbitMQ containers")
    void connectsToPostgresRedisAndRabbitMq() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(1);
        }

        String redisKey = "it:auth:infra:" + System.nanoTime();
        redisTemplate.opsForValue().set(redisKey, "ok", Duration.ofSeconds(30));
        assertThat(redisTemplate.opsForValue().get(redisKey)).isEqualTo("ok");

        Boolean rabbitOk = rabbitTemplate.execute(channel -> {
            String queueName = "it.auth.probe." + System.nanoTime();
            channel.queueDeclare(queueName, false, true, true, null);
            channel.queueDelete(queueName);
            return Boolean.TRUE;
        });
        assertThat(rabbitOk).isTrue();
    }
}
