// SPDX-License-Identifier: BSD-3-Clause

// AI Tool Usage BOM
// ------------------
//
// AI Tools Used:
// - Anthropic Claude Sonnet 4.6
//

package org.github.crac.systemd_appender;

import org.apache.logging.log4j.status.StatusLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class JournalSocket implements AutoCloseable {

    private static final String DEFAULT_SOCKET_PATH = "/run/systemd/journal/socket";

    private final String socketPath;
    private UnixDgramSocket socket;
    private boolean warnedMissing;

    JournalSocket() {
        this(DEFAULT_SOCKET_PATH);
    }

    JournalSocket(String socketPath) {
        this.socketPath = socketPath;
    }

    void send(byte[] data) {
        UnixDgramSocket s;
        try {
            s = ensureOpen();
        } catch (IOException e) {
            StatusLogger.getLogger().warn("Cannot open Unix DGRAM socket: {}", e.getMessage());
            return;
        }
        if (s == null) {
            return;
        }
        try {
            s.send(socketPath, data);
        } catch (IOException e) {
            StatusLogger.getLogger().warn(
                    "Failed to send to systemd journal, will retry on next event: {}", e.getMessage());
            close();
        }
    }

    private synchronized UnixDgramSocket ensureOpen() throws IOException {
        if (warnedMissing) {
            return null;
        }
        if (socket != null) {
            return socket;
        }
        if (!Files.exists(Path.of(socketPath))) {
            StatusLogger.getLogger().warn(
                    "Systemd journal socket not found at {}, dropping all log events", socketPath);
            warnedMissing = true;
            return null;
        }
        socket = new UnixDgramSocket();
        return socket;
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    /**
     * Resets state after a CRaC restore so the socket is re-opened on the next
     * log event. Also clears {@code warnedMissing} so a restored process that
     * now runs on a systemd host can connect even if it checkpointed elsewhere.
     */
    synchronized void afterRestore() {
        warnedMissing = false;
    }
}
