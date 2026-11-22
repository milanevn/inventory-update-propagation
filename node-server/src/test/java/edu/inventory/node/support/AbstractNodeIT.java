package edu.inventory.node.support;

import edu.inventory.node.InventoryNodeApplication;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Base class for integration tests (class name must end with {@code IT}; run by maven-failsafe in
 * {@code mvnw verify}). Boots the real node application as S1/PRIMARY against the dedicated TEST
 * PostgreSQL instance (never the S1..S4 dev databases) with a free gRPC port.
 *
 * <ul>
 *   <li>Local: {@code .\scripts\local-pg.ps1 init} creates the TEST instance on localhost:5549.
 *       If it is not running, ITs are SKIPPED with a message (they do not fail the build).</li>
 *   <li>CI ({@code CI=true}): a missing database FAILS the build.</li>
 *   <li>The schema is wiped (Flyway clean) and migrated once per Spring test context. Tests that
 *       need an empty table must delete their own rows; do not rely on execution order.</li>
 * </ul>
 * Override with TEST_DB_HOST, TEST_DB_PORT, TEST_DB_NAME, TEST_DB_USER, TEST_DB_PASSWORD.
 */
@SpringBootTest(classes = InventoryNodeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(AbstractNodeIT.CleanMigrateConfig.class)
public abstract class AbstractNodeIT {

    protected static final String DB_URL = "jdbc:postgresql://" + env("TEST_DB_HOST", "localhost") + ":"
            + env("TEST_DB_PORT", "5549") + "/" + env("TEST_DB_NAME", "inventory");
    protected static final String DB_USER = env("TEST_DB_USER", "inventory");
    protected static final String DB_PASSWORD = env("TEST_DB_PASSWORD", "local_only_change_me");
    protected static final int GRPC_PORT = freePort();

    @BeforeAll
    static void requireTestDatabase() {
        try (Connection ignored = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            // reachable
        } catch (SQLException e) {
            String message = "Test PostgreSQL not reachable at " + DB_URL + " (" + e.getMessage() + "). "
                    + "Start it with: .\\scripts\\local-pg.ps1 start -Node TEST";
            if ("true".equalsIgnoreCase(System.getenv("CI"))) {
                throw new IllegalStateException(message, e);
            }
            LoggerFactory.getLogger(AbstractNodeIT.class).warn("INTEGRATION TESTS SKIPPED - {}", message);
            Assumptions.abort("SKIPPED - " + message);
        }
    }

    @DynamicPropertySource
    static void nodeProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
        registry.add("spring.datasource.username", () -> DB_USER);
        registry.add("spring.datasource.password", () -> DB_PASSWORD);
        registry.add("spring.flyway.clean-disabled", () -> "false");
        registry.add("node.id", () -> "S1");
        registry.add("node.role", () -> "PRIMARY");
        registry.add("node.grpc-port", () -> GRPC_PORT);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CleanMigrateConfig {
        @Bean
        FlywayMigrationStrategy cleanThenMigrate() {
            return flyway -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
