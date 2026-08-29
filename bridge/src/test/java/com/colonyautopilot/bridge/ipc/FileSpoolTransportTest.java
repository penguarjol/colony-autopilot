package com.colonyautopilot.bridge.ipc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSpoolTransportTest {
    @TempDir
    Path root;

    @Test
    void createsCanonicalPrivateSpoolDirectories() throws Exception {
        new FileSpoolTransport(root).close();

        assertTrue(Files.isDirectory(root.resolve("requests")));
        assertTrue(Files.isDirectory(root.resolve("responses")));
        assertTrue(Files.isDirectory(root.resolve("rejected")));
    }

    @Test
    void completesAClaimUsingTheOriginalRequestToken() throws Exception {
        try (FileSpoolTransport transport = new FileSpoolTransport(root)) {
            Path request = root.resolve("requests/status-request.json");
            Files.writeString(request, "{\"request\":\"status\"}");
            Files.setPosixFilePermissions(request, PosixFilePermissions.fromString("rw-------"));

            BridgeTransport.InboundMessage message = transport.poll(1).get(0);
            assertEquals("status-request.json", message.token());

            transport.complete(message, "{\"status\":\"completed\"}");

            assertTrue(Files.isRegularFile(root.resolve("responses/status-request.json")));
            assertFalse(Files.exists(root.resolve("requests/.status-request.json.processing")));
        }
    }

    // A request the server cannot read used to abort the whole poll, so every
    // request behind it in filename order starved on every tick. Production
    // accumulated 37,000 spool files while this went unnoticed.
    @Test
    void oneUnreadableRequestDoesNotStarveTheRest() throws Exception {
        try (FileSpoolTransport transport = new FileSpoolTransport(root)) {
            Path poison = root.resolve("requests/aaa-poison.json");
            Files.createDirectory(poison);

            Path healthy = root.resolve("requests/zzz-healthy.json");
            Files.writeString(healthy, "{\"request\":\"status\"}");
            Files.setPosixFilePermissions(
                healthy, PosixFilePermissions.fromString("rw-------")
            );

            var messages = transport.poll(8);

            assertEquals(1, messages.size());
            assertEquals("zzz-healthy.json", messages.get(0).token());
            assertFalse(
                Files.exists(poison),
                "the unreadable request should be quarantined, not left to repoll"
            );
        }
    }

    // Tombstones only guard against replaying a request inside its freshness
    // window, so retaining them forever turned the response directory into a
    // 3 MB listing that slowed every lookup.
    @Test
    void prunesTombstonesOlderThanTheRetentionWindow() throws Exception {
        try (FileSpoolTransport transport = new FileSpoolTransport(root)) {
            Path fresh = root.resolve("responses/.fresh.json.done");
            Path expired = root.resolve("responses/.expired.json.done");
            Files.writeString(fresh, "");
            Files.writeString(expired, "");
            Files.setLastModifiedTime(
                expired,
                java.nio.file.attribute.FileTime.fromMillis(
                    System.currentTimeMillis() - java.time.Duration.ofHours(6).toMillis()
                )
            );

            transport.pruneExpired(System.currentTimeMillis());

            assertTrue(Files.exists(fresh), "a fresh tombstone still guards replay");
            assertFalse(Files.exists(expired), "an expired tombstone should be removed");
        }
    }

    @Test
    void prunesResponsePayloadsNobodyCollected() throws Exception {
        try (FileSpoolTransport transport = new FileSpoolTransport(root)) {
            Path abandoned = root.resolve("responses/status-abandoned.json");
            Files.writeString(abandoned, "{\"status\":\"completed\"}");
            Files.setLastModifiedTime(
                abandoned,
                java.nio.file.attribute.FileTime.fromMillis(
                    System.currentTimeMillis() - java.time.Duration.ofHours(6).toMillis()
                )
            );

            transport.pruneExpired(System.currentTimeMillis());

            assertFalse(Files.exists(abandoned));
        }
    }
}
