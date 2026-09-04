package io.github.jerryt92.j2agent.config.database;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 全新库：在 Flyway 迁移前执行 {@code sql/schema} 与 {@code sql/data} 引导脚本。
 */
@Slf4j
@Configuration
public class SqlBootstrapFlywayConfig {

    private static final String SCHEMA_SCRIPT = "sql/schema/postgresql/schemas.sql";
    private static final String DATA_SCRIPT = "sql/data/postgresql/data.sql";
    private static final String CORE_TABLE = "api_key_info";

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(
            DataSource dataSource) {
        return flyway -> {
            if (needsBootstrap(dataSource)) {
                log.info("Empty database detected, applying SQL bootstrap");
                runScript(dataSource, SCHEMA_SCRIPT);
                runScript(dataSource, DATA_SCRIPT);
            }
            flyway.migrate();
        };
    }

    private static boolean needsBootstrap(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             var tables = connection.getMetaData().getTables(null, "public", CORE_TABLE, new String[]{"TABLE"})) {
            return !tables.next();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to detect database bootstrap state", e);
        }
    }

    private static void runScript(DataSource dataSource, String classpathLocation) {
        var resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            throw new IllegalStateException("SQL bootstrap script not found: " + classpathLocation);
        }
        var populator = new ResourceDatabasePopulator(resource);
        populator.setSeparator(";");
        populator.setContinueOnError(false);
        try {
            populator.execute(dataSource);
            log.info("Applied SQL bootstrap script: {}", classpathLocation);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply SQL bootstrap script: " + classpathLocation, e);
        }
    }
}
