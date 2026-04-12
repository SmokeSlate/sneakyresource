package org.smokeslate.sneakyresource;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class SyncService {
    private final SneakyResourcePlugin plugin;
    private final Path serverRoot;

    SyncService(final SneakyResourcePlugin plugin) {
        this.plugin = plugin;
        this.serverRoot = Path.of("").toAbsolutePath().normalize();
    }

    SyncReport syncAll(final boolean allowReload) throws IOException {
        final FileConfiguration config = this.plugin.getConfig();
        final boolean syncResourcePack = config.getBoolean("resource-pack.enabled", true);
        final boolean syncDatapack = config.getBoolean("datapack.enabled", true);
        final boolean runReload = allowReload && config.getBoolean("run-minecraft-reload-after-sync", true);

        Path packZip = null;
        String sha1 = null;
        Path datapackDestination = null;

        if (syncResourcePack) {
            final Path resourcePackSource = resolveConfiguredPath("resource-pack.source-directory");
            final Path zipOutput = resolveConfiguredPath("resource-pack.output-zip");
            verifyDirectory(resourcePackSource, "resource-pack.source-directory");
            ensureParentDirectory(zipOutput);
            zipDirectory(resourcePackSource, zipOutput);
            packZip = zipOutput;
            sha1 = sha1(zipOutput);

            if (config.getBoolean("resource-pack.write-sha1-file", true)) {
                final Path sha1Output = resolveConfiguredPath("resource-pack.sha1-output");
                ensureParentDirectory(sha1Output);
                Files.writeString(sha1Output, sha1);
            }
        }

        if (syncDatapack) {
            final Path datapackSource = resolveConfiguredPath("datapack.source-directory");
            datapackDestination = resolveConfiguredPath("datapack.destination-directory");
            verifyDirectory(datapackSource, "datapack.source-directory");
            mirrorDirectory(datapackSource, datapackDestination);
        }

        if (runReload) {
            Bukkit.getScheduler().runTask(this.plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:reload"));
        }

        return new SyncReport(packZip, sha1, datapackDestination, runReload);
    }

    private Path resolveConfiguredPath(final String pathKey) {
        final String configured = this.plugin.getConfig().getString(pathKey);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Missing config value: " + pathKey);
        }

        final Path path = Path.of(configured);
        if (path.isAbsolute()) {
            return path.normalize();
        }

        final Path resolved = this.serverRoot.resolve(path).normalize();
        if (Files.exists(resolved) || !configured.startsWith("sneakyresource/")) {
            return resolved;
        }

        // Backward-compatible fallback for a repo checked out next to the server root.
        return this.serverRoot.resolveSibling(path).normalize();
    }

    private void verifyDirectory(final Path path, final String label) {
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(label + " must point to an existing directory: " + path);
        }
    }

    private void ensureParentDirectory(final Path path) throws IOException {
        final Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private void mirrorDirectory(final Path source, final Path destination) throws IOException {
        validateDestination(destination);
        deleteRecursively(destination);
        Files.createDirectories(destination);

        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                final Path relative = source.relativize(dir);
                if (shouldIgnore(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(destination.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                final Path relative = source.relativize(file);
                if (shouldIgnore(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                Files.copy(file, destination.resolve(relative), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void validateDestination(final Path destination) {
        if (destination.equals(this.serverRoot) || destination.getParent() == null) {
            throw new IllegalStateException("Refusing to sync into unsafe destination: " + destination);
        }
    }

    private void deleteRecursively(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void zipDirectory(final Path source, final Path zipPath) throws IOException {
        Files.deleteIfExists(zipPath);

        try (OutputStream fileOut = Files.newOutputStream(zipPath);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final Path relative = source.relativize(file);
                    if (shouldIgnore(relative)) {
                        return FileVisitResult.CONTINUE;
                    }
                    final ZipEntry entry = new ZipEntry(relative.toString().replace('\\', '/'));
                    zipOut.putNextEntry(entry);
                    try (InputStream input = Files.newInputStream(file)) {
                        input.transferTo(zipOut);
                    }
                    zipOut.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private String sha1(final Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }

        try (InputStream input = Files.newInputStream(file)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean shouldIgnore(final Path relative) {
        if (relative.getNameCount() == 0) {
            return false;
        }

        final String name = relative.getFileName().toString();
        return name.equals(".DS_Store") || name.equals("Thumbs.db");
    }
}
