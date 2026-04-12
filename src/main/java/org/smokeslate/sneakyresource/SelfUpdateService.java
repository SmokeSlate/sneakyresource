package org.smokeslate.sneakyresource;

import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

final class SelfUpdateService {
    private final SneakyResourcePlugin plugin;
    private final Path serverRoot;

    SelfUpdateService(final SneakyResourcePlugin plugin) {
        this.plugin = plugin;
        this.serverRoot = Path.of("").toAbsolutePath().normalize();
    }

    SelfUpdateReport updateFromRepository() throws IOException, InterruptedException {
        final FileConfiguration config = this.plugin.getConfig();
        if (!config.getBoolean("self-update.enabled", true)) {
            throw new IllegalStateException("self-update.enabled is false.");
        }

        final Path repoDirectory = resolveRepositoryDirectory("self-update.repository-directory");
        verifyGitRepository(repoDirectory);

        final String branch = config.getString("self-update.branch", "main");
        final String previousCommit = runCommand(repoDirectory, List.of("git", "rev-parse", "HEAD"), "read current commit").trim();
        runCommand(repoDirectory, List.of("git", "pull", "--ff-only", "origin", branch), "pull latest commits");
        final String currentCommit = runCommand(repoDirectory, List.of("git", "rev-parse", "HEAD"), "read updated commit").trim();
        final boolean repositoryChanged = !previousCommit.equals(currentCommit);

        final boolean buildWhenUnchanged = config.getBoolean("self-update.build-when-unchanged", false);
        final boolean shouldBuild = repositoryChanged || buildWhenUnchanged;
        final boolean syncAfterUpdate = config.getBoolean("self-update.sync-after-update", true);
        final boolean syncWhenUnchanged = config.getBoolean("self-update.sync-when-unchanged", true);

        Path builtJar = null;
        Path deployedJar = null;
        boolean syncRan = false;

        if (shouldBuild) {
            runBuild(repoDirectory);
            builtJar = locateBuiltJar(repoDirectory);

            if (config.getBoolean("self-update.stage-jar-in-update-folder", true)) {
                deployedJar = deployToUpdateFolder(builtJar);
            }

        }

        if (syncAfterUpdate && (shouldBuild || syncWhenUnchanged)) {
            this.plugin.setLastReport(this.plugin.getSyncService().syncAll(true));
            syncRan = true;
        }

        return new SelfUpdateReport(previousCommit, currentCommit, repositoryChanged, shouldBuild, builtJar, deployedJar, syncRan);
    }

    String currentRepositoryCommit() {
        try {
            final Path repoDirectory = resolveRepositoryDirectory("self-update.repository-directory");
            return runCommand(repoDirectory, List.of("git", "rev-parse", "HEAD"), "read current commit").trim();
        } catch (MissingRepositoryException exception) {
            return null;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            this.plugin.getLogger().warning("Failed to read current repository commit: " + exception.getMessage());
            return null;
        }
    }

    private void verifyGitRepository(final Path repoDirectory) {
        if (!Files.isDirectory(repoDirectory)) {
            throw new MissingRepositoryException("self-update.repository-directory does not exist: " + repoDirectory);
        }
        if (!Files.exists(repoDirectory.resolve(".git"))) {
            throw new MissingRepositoryException("self-update.repository-directory is not a git repository: " + repoDirectory);
        }
    }

    private void runBuild(final Path repoDirectory) throws IOException, InterruptedException {
        final String configured = this.plugin.getConfig().getString("self-update.build-command", "").trim();
        final List<String> command;

        if (!configured.isBlank()) {
            command = splitCommand(configured);
        } else if (isWindows()) {
            command = List.of("gradlew.bat", "build");
        } else {
            command = List.of("./gradlew", "build");
        }

        runCommand(repoDirectory, command, "build plugin");
    }

    private Path locateBuiltJar(final Path repoDirectory) throws IOException {
        final String configured = this.plugin.getConfig().getString("self-update.artifact-path", "").trim();
        if (!configured.isBlank()) {
            final Path artifact = repoDirectory.resolve(configured).normalize();
            if (!Files.isRegularFile(artifact)) {
                throw new IllegalStateException("Configured self-update.artifact-path does not exist: " + artifact);
            }
            return artifact;
        }

        final Path libsDirectory = repoDirectory.resolve("build").resolve("libs");
        if (!Files.isDirectory(libsDirectory)) {
            throw new IllegalStateException("Build output folder does not exist: " + libsDirectory);
        }

        try (Stream<Path> stream = Files.list(libsDirectory)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .filter(path -> !path.getFileName().toString().endsWith("-sources.jar"))
                .filter(path -> !path.getFileName().toString().endsWith("-javadoc.jar"))
                .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                .orElseThrow(() -> new IllegalStateException("No built plugin jar found in " + libsDirectory));
        }
    }

    private Path deployToUpdateFolder(final Path builtJar) throws IOException {
        final Server server = this.plugin.getServer();
        final Path updateFolder = server.getUpdateFolderFile().toPath();
        Files.createDirectories(updateFolder);
        final Path deployedJar = updateFolder.resolve(builtJar.getFileName().toString());
        Files.copy(builtJar, deployedJar, StandardCopyOption.REPLACE_EXISTING);
        return deployedJar;
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

        // Match SyncService path handling for the repo checked out next to the server root.
        return this.serverRoot.resolveSibling(path).normalize();
    }

    private Path resolveRepositoryDirectory(final String pathKey) {
        final String configured = this.plugin.getConfig().getString(pathKey);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Missing config value: " + pathKey);
        }

        final Path configuredPath = Path.of(configured);
        for (final Path candidate : repositoryCandidates(configuredPath)) {
            if (isGitRepository(candidate)) {
                return candidate;
            }
        }

        throw new MissingRepositoryException("self-update.repository-directory does not exist: " + resolveConfiguredPath(pathKey));
    }

    private List<Path> repositoryCandidates(final Path configuredPath) {
        final Set<Path> candidates = new LinkedHashSet<>();
        if (configuredPath.isAbsolute()) {
            candidates.add(configuredPath.normalize());
            return new ArrayList<>(candidates);
        }

        final Path primary = this.serverRoot.resolve(configuredPath).normalize();
        final Path sibling = this.serverRoot.resolveSibling(configuredPath).normalize();
        final Path pluginsParent = this.plugin.getDataFolder().toPath().getParent();

        candidates.add(primary);
        candidates.add(sibling);
        if (pluginsParent != null) {
            candidates.add(pluginsParent.resolve(configuredPath).normalize());
        }

        final String directoryName = configuredPath.getFileName() != null ? configuredPath.getFileName().toString() : configuredPath.toString();
        for (final Path base : List.copyOf(candidates)) {
            final Path parent = base.getParent();
            if (parent != null) {
                final Path caseInsensitive = resolveCaseInsensitiveChild(parent, directoryName);
                if (caseInsensitive != null) {
                    candidates.add(caseInsensitive.normalize());
                }
            }
        }

        return new ArrayList<>(candidates);
    }

    private boolean isGitRepository(final Path path) {
        return Files.isDirectory(path) && Files.exists(path.resolve(".git"));
    }

    private Path resolveCaseInsensitiveChild(final Path parent, final String expectedName) {
        if (!Files.isDirectory(parent)) {
            return null;
        }

        try (Stream<Path> stream = Files.list(parent)) {
            return stream
                .filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().equalsIgnoreCase(expectedName))
                .findFirst()
                .orElse(null);
        } catch (IOException exception) {
            return null;
        }
    }

    private List<String> splitCommand(final String commandLine) {
        final List<String> parts = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < commandLine.length(); i++) {
            final char ch = commandLine.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (Character.isWhitespace(ch) && !inQuotes) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }

        if (current.length() > 0) {
            parts.add(current.toString());
        }

        if (parts.isEmpty()) {
            throw new IllegalStateException("self-update.build-command is empty.");
        }
        return parts;
    }

    private String runCommand(final Path workingDirectory, final List<String> command, final String description) throws IOException, InterruptedException {
        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.redirectErrorStream(true);

        final Process process = processBuilder.start();
        final StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Failed to " + description + " (exit " + exitCode + "): " + tail(output.toString(), 30));
        }

        return output.toString();
    }

    private String tail(final String text, final int maxLines) {
        final String[] lines = text.split("\\R");
        final int start = Math.max(0, lines.length - maxLines);
        return String.join(System.lineSeparator(), Arrays.copyOfRange(lines, start, lines.length));
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
