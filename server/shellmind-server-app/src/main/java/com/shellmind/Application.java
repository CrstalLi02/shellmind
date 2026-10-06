package com.shellmind;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;
import java.nio.file.Path;
@SpringBootApplication
@Configurable
public class Application {

    public static void main(String[] args) {
        prepareLocalDataDirectory(args);
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    private static void prepareLocalDataDirectory(String[] args) {
        String systemProfile = System.getProperty("spring.profiles.active", "");
        String environmentProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        boolean local = systemProfile.contains("local")
                || (environmentProfile != null && environmentProfile.contains("local"))
                || java.util.Arrays.stream(args).anyMatch(arg -> arg.contains("spring.profiles.active=local"));
        if (local) {
            try {
                Path dataDir = Files.createDirectories(Path.of(System.getProperty("user.home"), ".shellmind", "data"));
                migrateLegacyLocalDatabase(dataDir);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to create local data directory", e);
            }
        }
    }

    /**
     * Before the rename, the local H2 database lived at ~/.walicode/data/walicode.mv.db.
     * If the new DB does not exist, copy it over (do not delete the old file) so history, SSH configs, etc. keep working.
     */
    private static void migrateLegacyLocalDatabase(Path dataDir) throws java.io.IOException {
        Path target = dataDir.resolve("shellmind.mv.db");
        Path legacy = Path.of(System.getProperty("user.home"), ".walicode", "data", "walicode.mv.db");
        if (Files.notExists(target) && Files.isRegularFile(legacy)) {
            Files.copy(legacy, target);
            System.out.println("Migrated legacy local database: " + legacy + " -> " + target);
        }
    }



}
