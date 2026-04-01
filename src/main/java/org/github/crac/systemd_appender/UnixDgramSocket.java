// SPDX-License-Identifier: BSD-3-Clause

// AI Tool Usage BOM
// ------------------
//
// AI Tools Used:
// - Anthropic Claude Sonnet 4.6
//

package org.github.crac.systemd_appender;

import java.io.IOException;

/** Common interface for AF_UNIX SOCK_DGRAM socket implementations. */
interface UnixDgramSocket extends AutoCloseable {
    void send(String path, byte[] data) throws IOException;
    @Override void close() throws IOException;
}
