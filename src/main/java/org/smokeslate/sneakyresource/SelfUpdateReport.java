package org.smokeslate.sneakyresource;

import java.nio.file.Path;

public record SelfUpdateReport(
    String previousCommit,
    String currentCommit,
    boolean updateAvailable,
    Path downloadedJar,
    Path deployedJar,
    boolean syncRan,
    boolean syncDeferredUntilRestart
) {
    public String summaryLine() {
        final String commitSegment = this.updateAvailable
            ? "updated to " + this.currentCommit
            : "already at " + this.currentCommit;
        final String downloadSegment = this.downloadedJar != null ? ", download complete" : ", download skipped";
        final String deploySegment = this.deployedJar != null ? ", staged for next restart" : "";
        final String syncSegment = this.syncRan
            ? ", sync complete"
            : this.syncDeferredUntilRestart ? ", sync deferred until restart" : "";
        return "SneakyResource self-update: " + commitSegment + downloadSegment + deploySegment + syncSegment;
    }
}
