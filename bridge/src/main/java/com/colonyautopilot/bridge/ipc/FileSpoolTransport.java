package com.colonyautopilot.bridge.ipc;

import com.colonyautopilot.bridge.ColonyAutopilotBridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class FileSpoolTransport implements BridgeTransport {
    public static final Path PRIVATE_DIRECTORY = Path.of("/home/container/.colony-autopilot");
    public static final Path INTENDED_SOCKET = PRIVATE_DIRECTORY.resolve("bridge.sock");
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );

    // A tombstone only has to outlive the freshness window that makes a replayed
    // request stale. An hour is two orders of magnitude beyond that and keeps
    // the response directory small enough to scan cheaply.
    private static final long RETENTION_MILLIS = 3_600_000L;
    private static final long PRUNE_INTERVAL_MILLIS = 300_000L;

    private final Path requests;
    private final Path responses;
    private final Path rejected;
    private long nextPruneAtMillis;

    public FileSpoolTransport() throws IOException {
        this(PRIVATE_DIRECTORY);
    }

    FileSpoolTransport(Path root) throws IOException {
        requirePrivateDirectory(root);
        requests = createPrivateDirectory(root.resolve("requests"));
        responses = createPrivateDirectory(root.resolve("responses"));
        rejected = createPrivateDirectory(root.resolve("rejected"));
        recoverClaims();
    }

    @Override
    public List<InboundMessage> poll(int maximum) throws IOException {
        if (maximum < 1) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        if (now >= nextPruneAtMillis) {
            nextPruneAtMillis = now + PRUNE_INTERVAL_MILLIS;
            pruneExpired(now);
        }
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(requests, "*.json")) {
            stream.forEach(candidates::add);
        }
        candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));

        List<InboundMessage> messages = new ArrayList<>();
        for (Path candidate : candidates) {
            if (messages.size() >= maximum) {
                break;
            }
            String token = candidate.getFileName().toString();
            if (Files.exists(tombstonePath(token), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(safeToken(responses, token), LinkOption.NOFOLLOW_LINKS)) {
                Path duplicate = rejected.resolve(
                    "duplicate." + UUID.randomUUID() + "." + candidate.getFileName()
                );
                moveAtomically(candidate, duplicate);
                continue;
            }
            // One unreadable request must not abort the cycle. A single file the
            // server cannot read -- a stray root-owned drop, a truncated write --
            // otherwise starves every request behind it in filename order, on
            // every tick, until an operator notices.
            try {
                validateRequestFile(candidate);
                Path claimed = claimPath(candidate.getFileName().toString());
                try {
                    moveAtomically(candidate, claimed);
                } catch (java.nio.file.NoSuchFileException ignored) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(claimed);
                messages.add(
                    new InboundMessage(token, new String(bytes, StandardCharsets.UTF_8))
                );
            } catch (IOException exception) {
                quarantine(candidate, token, exception);
            }
        }
        return List.copyOf(messages);
    }

    /**
     * Drops tombstones and uncollected responses past the retention window.
     * Pruning failures are not fatal: a spool that cannot be tidied is still a
     * spool that works.
     */
    void pruneExpired(long nowMillis) {
        long cutoff = nowMillis - RETENTION_MILLIS;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(responses)) {
            for (Path entry : stream) {
                try {
                    if (Files.getLastModifiedTime(entry).toMillis() < cutoff) {
                        Files.deleteIfExists(entry);
                    }
                } catch (IOException ignored) {
                    // Raced with a concurrent read or write; try again next sweep.
                }
            }
        } catch (IOException exception) {
            ColonyAutopilotBridge.LOGGER.warn(
                "Could not prune the IPC response spool", exception
            );
        }
    }

    private void quarantine(Path candidate, String token, IOException cause) {
        ColonyAutopilotBridge.LOGGER.error(
            "Quarantining unreadable IPC request {}", token, cause
        );
        Path target = rejected.resolve(
            "unreadable." + UUID.randomUUID() + "." + candidate.getFileName()
        );
        try {
            moveAtomically(candidate, target);
        } catch (IOException failure) {
            // The claim may already have been taken; deleting keeps the queue
            // moving rather than reprocessing the same poison every tick.
            try {
                Files.deleteIfExists(claimPath(token));
                Files.deleteIfExists(candidate);
            } catch (IOException ignored) {
                ColonyAutopilotBridge.LOGGER.error(
                    "Could not clear unreadable IPC request {}", token, failure
                );
            }
        }
    }

    @Override
    public void complete(InboundMessage message, String responseJson) throws IOException {
        writeResult(message, responseJson, responses);
    }

    @Override
    public void reject(InboundMessage message, String reason) throws IOException {
        writeResult(message, reason, rejected);
    }

    private void writeResult(InboundMessage message, String body, Path destination) throws IOException {
        Path source = claimPath(message.token());
        Path target = safeToken(destination, message.token());
        Path temporary = destination.resolve("." + message.token() + "." + UUID.randomUUID() + ".tmp");
        Files.writeString(temporary, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(temporary, FILE_PERMISSIONS);
        moveAtomically(temporary, target);
        Path tombstone = tombstonePath(message.token());
        if (!Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(tombstone, "", StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(tombstone, FILE_PERMISSIONS);
        }
        Files.deleteIfExists(source);
    }

    private void recoverClaims() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(requests, ".*.json.processing")) {
            for (Path claim : stream) {
                String name = claim.getFileName().toString();
                String token = name.substring(1, name.length() - ".processing".length());
                Path request = safeToken(requests, token);
                if (Files.exists(request, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(claim);
                } else {
                    moveAtomically(claim, request);
                }
            }
        }
    }

    private Path claimPath(String token) throws IOException {
        safeToken(requests, token);
        return requests.resolve("." + token + ".processing");
    }

    private Path tombstonePath(String token) throws IOException {
        safeToken(responses, token);
        return responses.resolve("." + token + ".done");
    }

    private static Path safeToken(Path directory, String token) throws IOException {
        if (!token.matches("[A-Za-z0-9._-]+\\.json")) {
            throw new IOException("Unsafe spool token");
        }
        Path path = directory.resolve(token).normalize();
        if (!path.getParent().equals(directory)) {
            throw new IOException("Spool token escaped its directory");
        }
        return path;
    }

    private static void validateRequestFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path)
            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || Files.size(path) > MAX_REQUEST_BYTES) {
            throw new IOException("Unsafe request file: " + path.getFileName());
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!FILE_PERMISSIONS.containsAll(permissions)) {
            throw new IOException("Request file must not be accessible by group or other users");
        }
    }

    private static void requirePrivateDirectory(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            throw new IOException("IPC root must not be a symbolic link");
        }
        createPrivateDirectory(root);
    }

    private static Path createPrivateDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("IPC path is not a private directory: " + path);
        }
        Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS);
        return path;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("IPC spool filesystem must support atomic moves", exception);
        }
    }
}
