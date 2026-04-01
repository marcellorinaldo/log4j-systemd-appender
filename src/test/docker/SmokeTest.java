// SPDX-License-Identifier: BSD-3-Clause

// AI Tool Usage BOM
// ------------------
//
// AI Tools Used:
// - Anthropic Claude Sonnet 4.6
//

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

/**
 * Minimal smoke-test application that sends a handful of structured log events
 * to the systemd journal via the JournaldAppender.  Run inside a container
 * with /run/systemd/journal/socket bind-mounted from the host.
 */
public class SmokeTest {

    private static final Logger log = LogManager.getLogger(SmokeTest.class);

    public static void main(String[] args) {
        ThreadContext.put("requestId", "abc-123");
        ThreadContext.put("user", "alice");

        log.info("Smoke test started — platform: {} libc: {}",
                System.getProperty("os.arch"),
                detectLibc());

        log.warn("This is a warning message");

        try {
            riskyOperation();
        } catch (RuntimeException e) {
            log.error("Caught exception during risky operation", e);
        }

        log.info("Smoke test completed");

        System.out.println("Done. Inspect with:");
        System.out.println("  journalctl -f SYSLOG_IDENTIFIER=log4j-docker-smoke");
    }

    private static void riskyOperation() {
        throw new RuntimeException("intentional test exception");
    }

    private static String detectLibc() {
        try {
            boolean musl = java.nio.file.Files.lines(java.nio.file.Path.of("/proc/self/maps"))
                    .anyMatch(l -> l.contains("ld-musl-"));
            return musl ? "musl" : "glibc";
        } catch (java.io.IOException e) {
            return "unknown";
        }
    }
}
