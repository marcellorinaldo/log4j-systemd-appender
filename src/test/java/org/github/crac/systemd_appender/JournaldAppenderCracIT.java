// SPDX-License-Identifier: BSD-3-Clause

// AI Tool Usage BOM
// ------------------
//
// AI Tools Used:
// - Anthropic Claude Sonnet 4.6
//

package org.github.crac.systemd_appender;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for CRaC checkpoint/restore behaviour.
 * Requires a CRaC-enabled JVM and -XX:CRaCEngine=simengine.
 * Run with: mvn verify -Pcrac-integration-test
 */
class JournaldAppenderCracIT {

    private static final Logger log = LogManager.getLogger(JournaldAppenderCracIT.class);

    @Test
    void cracCheckpointRestore_socketReopenedAfterRestore() throws Exception {
        String markerBefore = "crac-before-" + UUID.randomUUID();
        log.info(markerBefore);

        org.crac.Core.checkpointRestore();

        String markerAfter = "crac-after-" + UUID.randomUUID();
        log.info(markerAfter);

        // Give journald a moment to flush
        Thread.sleep(500);

        var result = new ProcessBuilder(
                "journalctl", "--user", "-n", "50", "--no-pager", "-o", "export",
                "SYSLOG_IDENTIFIER=log4j-systemd-it")
                .redirectErrorStream(true)
                .start();
        String output = new String(result.getInputStream().readAllBytes());
        result.waitFor();

        assertTrue(output.contains(markerBefore),
                "Expected pre-checkpoint marker '" + markerBefore + "' not found in journal:\n" + output);
        assertTrue(output.contains(markerAfter),
                "Expected post-restore marker '" + markerAfter + "' not found in journal:\n" + output);
    }
}
