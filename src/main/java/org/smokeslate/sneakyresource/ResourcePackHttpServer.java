package org.smokeslate.sneakyresource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ResourcePackHttpServer {
    private final SneakyResourcePlugin plugin;
    private HttpServer server;
    private ExecutorService executor;

    ResourcePackHttpServer(final SneakyResourcePlugin plugin) {
        this.plugin = plugin;
    }

    void start() throws IOException {
        if (!isEnabled() || this.server != null) {
            return;
        }

        final FileConfiguration config = this.plugin.getConfig();
        final int port = config.getInt("resource-pack.self-hosted.port", 2053);
        final String zipPath = normalizePath(config.getString("resource-pack.self-hosted.zip-path", "/sasquatchresourcepack.zip"));
        final String sha1Path = normalizePath(config.getString("resource-pack.self-hosted.sha1-path", "/sasquatchresourcepack.zip.sha1"));

        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "SneakyResource-PackHttp");
            thread.setDaemon(true);
            return thread;
        });
        this.server.setExecutor(this.executor);
        this.server.createContext(zipPath, exchange -> serveFile(exchange, resolveConfiguredPath("resource-pack.output-zip"), "application/zip"));
        this.server.createContext(sha1Path, exchange -> serveFile(exchange, resolveConfiguredPath("resource-pack.sha1-output"), "text/plain; charset=utf-8"));
        this.server.start();
        this.plugin.getLogger().info("SneakyResource self-hosted resource pack server started on port " + port + ".");
    }

    void stop() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }
        if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
        }
    }

    boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("resource-pack.self-hosted.enabled", true);
    }

    private void serveFile(final HttpExchange exchange, final Path path, final String contentType) throws IOException {
        try {
            if (!Files.isRegularFile(path)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            final long contentLength = Files.size(path);
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            exchange.sendResponseHeaders(200, contentLength);
            try (OutputStream output = exchange.getResponseBody()) {
                Files.copy(path, output);
            }
        } finally {
            exchange.close();
        }
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

        final Path serverRoot = Path.of("").toAbsolutePath().normalize();
        final Path resolved = serverRoot.resolve(path).normalize();
        if (Files.exists(resolved) || !configured.startsWith("sneakyresource/")) {
            return resolved;
        }

        return serverRoot.resolveSibling(path).normalize();
    }

    private String normalizePath(final String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
