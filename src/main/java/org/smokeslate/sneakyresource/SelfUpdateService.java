package org.smokeslate.sneakyresource;

import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

final class SelfUpdateService {
    private final SneakyResourcePlugin plugin;
    private final HttpClient httpClient;

    SelfUpdateService(final SneakyResourcePlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newHttpClient();
    }

    SelfUpdateReport updateFromRepository() throws IOException, InterruptedException {
        final FileConfiguration config = this.plugin.getConfig();
        if (!config.getBoolean("self-update.enabled", true)) {
            throw new IllegalStateException("self-update.enabled is false.");
        }

        final BuildInfo localBuild = readBundledBuildInfo();
        final BuildInfo remoteBuild = fetchRemoteBuildInfo();
        final String previousCommit = localBuild.commit();
        final String currentCommit = remoteBuild.commit();

        final String jarUrl = requiredUrl("self-update.jar-url");
        final String remoteSha1 = fetchRemoteSha1(requiredUrl("self-update.jar-sha1-url"));
        final String localSha1 = currentPluginJarSha1();
        final boolean updateAvailable = isUpdateAvailable(localBuild, remoteBuild, localSha1, remoteSha1);
        final boolean syncAfterUpdate = config.getBoolean("self-update.sync-after-update", true);
        final boolean syncWhenUnchanged = config.getBoolean("self-update.sync-when-unchanged", true);

        Path downloadedJar = null;
        Path deployedJar = null;
        boolean syncRan = false;

        if (updateAvailable) {
            downloadedJar = downloadJar(jarUrl, remoteSha1);
            if (config.getBoolean("self-update.stage-jar-in-update-folder", true)) {
                deployedJar = deployToUpdateFolder(downloadedJar);
            } else {
                deployedJar = replaceCurrentPluginJar(downloadedJar);
            }
        }

        if (syncAfterUpdate && (updateAvailable || syncWhenUnchanged)) {
            this.plugin.setLastReport(this.plugin.getSyncService().syncAll(true));
            syncRan = true;
        }

        return new SelfUpdateReport(previousCommit, currentCommit, updateAvailable, downloadedJar, deployedJar, syncRan);
    }

    @Nullable
    String currentBuildCommit() {
        return readBundledBuildInfo().commit();
    }

    private BuildInfo readBundledBuildInfo() {
        try (InputStream input = this.plugin.getResource("build-info.properties")) {
            if (input == null) {
                return BuildInfo.unknown();
            }
            final Properties properties = new Properties();
            properties.load(input);
            return BuildInfo.from(properties);
        } catch (IOException exception) {
            this.plugin.getLogger().warning("Failed to read bundled build info: " + exception.getMessage());
            return BuildInfo.unknown();
        }
    }

    private BuildInfo fetchRemoteBuildInfo() throws IOException, InterruptedException {
        final String buildInfoUrl = requiredUrl("self-update.build-info-url");
        final HttpRequest request = HttpRequest.newBuilder(URI.create(buildInfoUrl)).GET().build();
        final HttpResponse<InputStream> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to fetch remote build info: HTTP " + response.statusCode());
        }

        try (InputStream input = response.body()) {
            final Properties properties = new Properties();
            properties.load(input);
            return BuildInfo.from(properties);
        }
    }

    private String requiredUrl(final String key) {
        final String configured = this.plugin.getConfig().getString(key, "").trim();
        if (configured.isBlank()) {
            throw new IllegalStateException("Missing config value: " + key);
        }
        return configured;
    }

    private String fetchRemoteSha1(final String sha1Url) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(sha1Url)).GET().build();
        final HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to fetch remote plugin sha1: HTTP " + response.statusCode());
        }

        final String body = response.body().trim();
        if (body.isBlank()) {
            throw new IOException("Remote plugin sha1 response was empty.");
        }

        final int separator = body.indexOf(' ');
        return separator >= 0 ? body.substring(0, separator).trim() : body;
    }

    private String currentPluginJarSha1() throws IOException {
        return sha1(resolveCurrentPluginJar());
    }

    private boolean isUpdateAvailable(
        final BuildInfo localBuild,
        final BuildInfo remoteBuild,
        final String localSha1,
        final String remoteSha1
    ) {
        if (localBuild.hasKnownCommit() && remoteBuild.hasKnownCommit()) {
            return !localBuild.commit().equalsIgnoreCase(remoteBuild.commit());
        }

        return !remoteSha1.equalsIgnoreCase(localSha1);
    }

    private Path downloadJar(final String jarUrl, final String expectedSha1) throws IOException, InterruptedException {
        final Path tempJar = Files.createTempFile("sneakyresource-update-", ".jar");
        final HttpRequest request = HttpRequest.newBuilder(URI.create(jarUrl)).GET().build();
        final HttpResponse<Path> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempJar));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(tempJar);
            throw new IOException("Failed to download updated plugin jar: HTTP " + response.statusCode());
        }

        final String actualSha1 = sha1(tempJar);
        if (!expectedSha1.equalsIgnoreCase(actualSha1)) {
            Files.deleteIfExists(tempJar);
            throw new IOException("Downloaded plugin jar sha1 mismatch. Expected " + expectedSha1 + " but got " + actualSha1);
        }

        return tempJar;
    }

    private Path deployToUpdateFolder(final Path downloadedJar) throws IOException {
        final Server server = this.plugin.getServer();
        final Path updateFolder = server.getUpdateFolderFile().toPath();
        Files.createDirectories(updateFolder);

        final Path currentJar = resolveCurrentPluginJar();
        final Path deployedJar = updateFolder.resolve(currentJar.getFileName().toString());
        Files.copy(downloadedJar, deployedJar, StandardCopyOption.REPLACE_EXISTING);
        return deployedJar;
    }

    private Path replaceCurrentPluginJar(final Path downloadedJar) throws IOException {
        final Path currentJar = resolveCurrentPluginJar();
        Files.copy(downloadedJar, currentJar, StandardCopyOption.REPLACE_EXISTING);
        return currentJar;
    }

    private Path resolveCurrentPluginJar() throws IOException {
        final Path currentJar;
        try {
            currentJar = Path.of(this.plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).normalize();
        } catch (Exception exception) {
            throw new IOException("Unable to locate current plugin jar.", exception);
        }

        if (!Files.isRegularFile(currentJar)) {
            throw new IOException("Current plugin is not running from a jar file: " + currentJar);
        }
        return currentJar;
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

    private record BuildInfo(String version, String commit) {
        static BuildInfo from(final Properties properties) {
            return new BuildInfo(
                valueOrUnknown(properties.getProperty("version")),
                valueOrUnknown(properties.getProperty("commit"))
            );
        }

        static BuildInfo unknown() {
            return new BuildInfo("unknown", "unknown");
        }

        boolean hasKnownCommit() {
            return !"unknown".equalsIgnoreCase(this.commit);
        }

        private static String valueOrUnknown(@Nullable final String value) {
            return value == null || value.isBlank() ? "unknown" : value.trim();
        }
    }
}
