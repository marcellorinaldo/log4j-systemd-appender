// SPDX-License-Identifier: BSD-3-Clause

// AI Tool Usage BOM
// ------------------
//
// AI Tools Used:
// - Anthropic Claude Sonnet 4.6
//

package org.github.crac.systemd_appender;

import java.io.IOException;

/**
 * JNI wrapper around a single AF_UNIX SOCK_DGRAM file descriptor.
 * All journal protocol encoding is done in Java; this class only handles
 * the OS-level socket operations.
 *
 * <p>On JDK 22+ this class is used only when the system property
 * {@code org.github.crac.systemd_appender.jni=true} is set; otherwise the
 * FFM implementation in {@code META-INF/versions/22/} is selected automatically
 * by the Multi-Release JAR mechanism.
 */
final class JniUnixDgramSocket implements UnixDgramSocket {

    static {
        NativeLoader.loadLibrary("journalsocket");
    }

    /** Opens an AF_UNIX SOCK_DGRAM socket. Returns the fd, or -errno on failure. */
    private static native int open0();

    /** Calls sendto(2) to path. Returns 0 on success, -errno on failure. */
    private static native int send0(int fd, String path, byte[] data, int length);

    /** Calls close(2) on the fd. */
    private static native void close0(int fd);

    private final int fd;

    JniUnixDgramSocket() throws IOException {
        int result = open0();
        if (result < 0) {
            throw new IOException("socket(AF_UNIX, SOCK_DGRAM) failed: errno " + (-result));
        }
        this.fd = result;
    }

    public void send(String path, byte[] data) throws IOException {
        int result = send0(fd, path, data, data.length);
        if (result < 0) {
            throw new IOException("sendto(" + path + ") failed: errno " + (-result));
        }
    }

    @Override
    public void close() throws IOException {
        close0(fd);
    }
}
