package org.smokeslate.sneakyresource;

import java.nio.file.Path;

public record SyncReport(
    Path resourcePackZip,
    String resourcePackSha1,
    Path datapackDestination,
    boolean reloadTriggered
) {
    public String summaryLine() {
        final String reloadSegment = this.reloadTriggered ? "reload queued" : "reload skipped";
        return "SneakyResource sync complete: " + reloadSegment;
    }
}
