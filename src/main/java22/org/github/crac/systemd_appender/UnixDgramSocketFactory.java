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
 * Factory for {@link UnixDgramSocket} on JDK 22+.  Selected automatically via the
 * Multi-Release JAR in preference to the JDK 17–21 version in the JAR root.
 *
 * <p>Returns {@link FfmUnixDgramSocket} by default.  Set the system property
 * {@code org.github.crac.systemd_appender.jni=true} to return
 * {@link JniUnixDgramSocket} instead (useful for testing the native library on
 * a modern JDK).
 */
final class UnixDgramSocketFactory {

    private static final String JNI_PROP = "org.github.crac.systemd_appender.jni";

    private UnixDgramSocketFactory() {}

    static UnixDgramSocket create() throws IOException {
        return Boolean.getBoolean(JNI_PROP)
                ? new JniUnixDgramSocket()
                : new FfmUnixDgramSocket();
    }
}
