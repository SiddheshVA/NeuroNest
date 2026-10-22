package com.neuronest.backend.securitysuite;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One real PostgreSQL process for the whole security suite, started on first use and stopped when the
 * test JVM exits. No Docker: the binaries come from the embedded-postgres test dependency. Also owns
 * the suite's private document storage folder.
 */
final class SuiteDatabase {

    private static EmbeddedPostgres postgres;
    private static Path storageDir;

    private SuiteDatabase() {}

    static synchronized String jdbcUrl() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start the embedded PostgreSQL for the security suite.", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // The test run is ending anyway.
                }
            }));
        }
        return postgres.getJdbcUrl("postgres", "postgres");
    }

    /** The private storage root used as APP_STORAGE_DIR in the suite. */
    static synchronized Path storageDir() {
        if (storageDir == null) {
            try {
                storageDir = Files.createTempDirectory("security-suite-storage-").toAbsolutePath();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return storageDir;
    }
}
