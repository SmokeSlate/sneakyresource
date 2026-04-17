package org.smokeslate.sneakyresource;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
        final boolean usingNexo = this.plugin.isNexoIntegrationActive();
        final boolean syncResourcePack = config.getBoolean("resource-pack.enabled", true) && !usingNexo;
        final boolean syncDatapack = config.getBoolean("datapack.enabled", true) && !usingNexo;
        final boolean runReload = allowReload && config.getBoolean("run-minecraft-reload-after-sync", true);

        Path packZip = null;
        String sha1 = null;
        String resourcePackUrl = null;
        Path datapackDestination = null;

        if (usingNexo) {
            syncNexoAssets();
            deleteManagedDirectoryIfConfigured("datapack.destination-directory");
            if (runReload) {
                Bukkit.getScheduler().runTask(this.plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "nexo reload"));
            }
        }

        if (syncResourcePack) {
            final Path resourcePackSource = resolveSourceDirectory(
                "resource-pack.source-directory",
                "bundled/resourcepack/",
                "bundled/resourcepack"
            );
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

            resourcePackUrl = withCacheBuster(configuredPackUrl(), sha1);
            final String remoteSha1 = fetchRemoteSha1IfConfigured();
            if (remoteSha1 != null && !remoteSha1.isBlank()) {
                sha1 = remoteSha1;
                resourcePackUrl = withCacheBuster(configuredPackUrl(), sha1);
            }
        }

        if (syncDatapack) {
            final Path datapackSource = resolveSourceDirectory(
                "datapack.source-directory",
                "bundled/datapack/",
                "bundled/datapack"
            );
            datapackDestination = resolveConfiguredPath("datapack.destination-directory");
            verifyDirectory(datapackSource, "datapack.source-directory");
            mirrorDirectory(datapackSource, datapackDestination);
        }

        if (runReload) {
            Bukkit.getScheduler().runTask(this.plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:reload"));
        }

        return new SyncReport(packZip, sha1, resourcePackUrl, datapackDestination, runReload);
    }

    private void syncNexoAssets() throws IOException {
        final Path nexoSource = resolveSourceDirectory(
            "nexo.source-directory",
            "bundled/nexo/",
            "bundled/nexo"
        );
        final Path nexoRoot = resolveConfiguredPath("nexo.root-directory");

        syncManagedDirectory(nexoSource.resolve("items"), nexoRoot.resolve("items").resolve("sneakyresource"));
        syncManagedDirectory(nexoSource.resolve("recipes").resolve("shaped"), nexoRoot.resolve("recipes").resolve("shaped").resolve("sneakyresource"));
        syncManagedDirectory(nexoSource.resolve("recipes").resolve("stonecutting"), nexoRoot.resolve("recipes").resolve("stonecutting").resolve("sneakyresource"));
        final Path externalPackRoot = nexoRoot.resolve("pack").resolve("external_packs").resolve("sasquatchresourcepack");
        deleteManagedDirectory(externalPackRoot);

        final Path resourcePackSource = resolveSourceDirectory(
            "resource-pack.source-directory",
            "bundled/resourcepack/",
            "bundled/resourcepack"
        );
        syncManagedDirectory(resourcePackSource, externalPackRoot);
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

    private Path resolveSourceDirectory(final String pathKey, final String bundledRoot, final String extractedSubdirectory) throws IOException {
        final String configured = this.plugin.getConfig().getString(pathKey, "").trim();
        if (!configured.isBlank()) {
            final Path configuredPath = resolveConfiguredPath(pathKey);
            if (Files.isDirectory(configuredPath)) {
                return configuredPath;
            }
        }

        return extractBundledDirectory(bundledRoot, extractedSubdirectory);
    }

    private void verifyDirectory(final Path path, final String label) {
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(label + " must point to an existing directory: " + path);
        }
    }

    private void syncManagedDirectory(final Path source, final Path destination) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }

        mirrorDirectory(source, destination);
    }

    private void deleteManagedDirectoryIfConfigured(final String pathKey) throws IOException {
        final String configured = this.plugin.getConfig().getString(pathKey, "").trim();
        if (configured.isBlank()) {
            return;
        }

        final Path path = resolveConfiguredPath(pathKey);
        deleteManagedDirectory(path);
    }

    private void deleteManagedDirectory(final Path path) throws IOException {
        validateDestination(path);
        deleteRecursively(path);
    }

    private void ensureParentDirectory(final Path path) throws IOException {
        final Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private Path extractBundledDirectory(final String bundledRoot, final String extractedSubdirectory) throws IOException {
        final Path destination = this.plugin.getDataFolder().toPath().resolve(extractedSubdirectory).normalize();
        deleteRecursively(destination);
        Files.createDirectories(destination);

        final Path codeSource;
        try {
            codeSource = Path.of(this.plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception exception) {
            throw new IOException("Unable to locate plugin jar for bundled asset extraction.", exception);
        }

        if (Files.isDirectory(codeSource)) {
            final Path sourceDirectory = codeSource.resolve(bundledRoot).normalize();
            if (!Files.isDirectory(sourceDirectory)) {
                throw new IOException("Bundled source directory not found: " + sourceDirectory);
            }
            mirrorDirectory(sourceDirectory, destination);
            return destination;
        }

        try (JarFile jarFile = new JarFile(codeSource.toFile())) {
            final Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                final String name = entry.getName();
                if (!name.startsWith(bundledRoot) || entry.isDirectory()) {
                    continue;
                }

                final String relative = name.substring(bundledRoot.length());
                final Path output = destination.resolve(relative).normalize();
                ensureParentDirectory(output);
                try (InputStream input = jarFile.getInputStream(entry)) {
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        return destination;
    }

    @Nullable
    String configuredPackUrl() {
        final String selfHosted = configuredSelfHostedPackUrl();
        if (selfHosted != null && !selfHosted.isBlank()) {
            return selfHosted;
        }

        final String configured = this.plugin.getConfig().getString("resource-pack.public-url");
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return configured.trim();
    }

    @Nullable
    String configuredPackSha1Url() {
        final String selfHosted = configuredSelfHostedPackSha1Url();
        if (selfHosted != null && !selfHosted.isBlank()) {
            return selfHosted;
        }

        final String configured = this.plugin.getConfig().getString("resource-pack.sha1-url");
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return configured.trim();
    }

    boolean isPackRequired() {
        return this.plugin.getConfig().getBoolean("resource-pack.required", false);
    }

    boolean isSelfHostedPackEnabled() {
        return this.plugin.getConfig().getBoolean("resource-pack.self-hosted.enabled", true);
    }

    @Nullable
    String configuredPackPrompt() {
        final String configured = this.plugin.getConfig().getString("resource-pack.prompt");
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return configured;
    }

    @Nullable
    private String configuredSelfHostedPackUrl() {
        return configuredSelfHostedUrl("resource-pack.self-hosted.zip-path");
    }

    @Nullable
    private String configuredSelfHostedPackSha1Url() {
        return configuredSelfHostedUrl("resource-pack.self-hosted.sha1-path");
    }

    @Nullable
    private String configuredSelfHostedUrl(final String pathKey) {
        if (!isSelfHostedPackEnabled()) {
            return null;
        }

        final String publicBaseUrl = this.plugin.getConfig().getString("resource-pack.self-hosted.public-base-url", "").trim();
        final String configuredPath = this.plugin.getConfig().getString(pathKey, "").trim();
        final String normalizedPath = configuredPath.startsWith("/") ? configuredPath : "/" + configuredPath;
        if (!publicBaseUrl.isBlank()) {
            return joinUrl(normalizePublicBaseUrl(publicBaseUrl), normalizedPath);
        }

        final String hostname = this.plugin.getConfig().getString("resource-pack.self-hosted.hostname", "").trim();
        if (hostname.isBlank()) {
            return null;
        }

        final String scheme = this.plugin.getConfig().getString("resource-pack.self-hosted.scheme", "http").trim();
        final int port = this.plugin.getConfig().getInt(
            "resource-pack.self-hosted.public-port",
            this.plugin.getConfig().getInt("resource-pack.self-hosted.port", 2053)
        );
        final String defaultPortlessUrl = scheme + "://" + hostname;
        return joinUrl(defaultPortlessUrl + portSegment(scheme, port), normalizedPath);
    }

    private String joinUrl(final String baseUrl, final String normalizedPath) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + normalizedPath : baseUrl + normalizedPath;
    }

    private String normalizePublicBaseUrl(final String baseUrl) {
        try {
            final URI uri = URI.create(baseUrl);
            final String scheme = uri.getScheme();
            final int port = uri.getPort();
            if ("http".equalsIgnoreCase(scheme) && isHttpsOnlyPublicPort(port)) {
                return "https://" + uri.getRawAuthority() + normalizedRawPath(uri);
            }
        } catch (IllegalArgumentException exception) {
            this.plugin.getLogger().warning("Invalid resource-pack.self-hosted.public-base-url: " + baseUrl);
        }

        return baseUrl;
    }

    private String normalizedRawPath(final URI uri) {
        final String path = uri.getRawPath();
        return path == null || path.isBlank() ? "" : path;
    }

    private boolean isHttpsOnlyPublicPort(final int port) {
        return port == 2053 || port == 2083 || port == 2087 || port == 2096 || port == 8443;
    }

    private String portSegment(final String scheme, final int port) {
        if (port <= 0) {
            return "";
        }
        if (("http".equalsIgnoreCase(scheme) && port == 80) || ("https".equalsIgnoreCase(scheme) && port == 443)) {
            return "";
        }
        return ":" + port;
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

    @Nullable
    private String withCacheBuster(@Nullable final String url, @Nullable final String sha1) {
        if (url == null || url.isBlank()) {
            return url;
        }
        if (sha1 == null || sha1.isBlank()) {
            return url;
        }

        final String separator = url.contains("?") ? "&" : "?";
        return url + separator + "v=" + sha1;
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

    @Nullable
    private String fetchRemoteSha1IfConfigured() {
        if (isSelfHostedPackEnabled()) {
            return null;
        }

        final String sha1Url = configuredPackSha1Url();
        if (sha1Url == null || sha1Url.isBlank()) {
            return null;
        }

        try {
            final HttpClient client = HttpClient.newHttpClient();
            final HttpRequest request = HttpRequest.newBuilder(URI.create(sha1Url)).GET().build();
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                this.plugin.getLogger().warning("Failed to fetch remote resource pack sha1: HTTP " + response.statusCode());
                return null;
            }

            final String body = response.body().trim();
            if (body.isBlank()) {
                return null;
            }

            final int separator = body.indexOf(' ');
            return separator >= 0 ? body.substring(0, separator).trim() : body;
        } catch (Exception exception) {
            this.plugin.getLogger().warning("Failed to fetch remote resource pack sha1: " + exception.getMessage());
            return null;
        }
    }
}
