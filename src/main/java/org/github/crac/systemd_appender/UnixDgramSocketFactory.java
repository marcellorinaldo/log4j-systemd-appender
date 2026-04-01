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
 * Factory for {@link UnixDgramSocket}.  On JDK 17–21 always returns the JNI
 * implementation.  On JDK 22+ the Multi-Release JAR selects the overriding
 * version of this class, which may return either the FFM or JNI implementation.
 */
final class UnixDgramSocketFactory {

    private UnixDgramSocketFactory() {}

    static UnixDgramSocket create() throws IOException {
        return new JniUnixDgramSocket();
    }
}
