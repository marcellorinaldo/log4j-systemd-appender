// SPDX-License-Identifier: BSD-3-Clause

// AI Tool Usage BOM
// ------------------
//
// AI Tools Used:
// - Anthropic Claude Sonnet 4.6
//

package org.github.crac.systemd_appender;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JournaldAppenderTest {

    @Test
    void append_sendsStructuredFieldsToJournalSocket() throws Exception {
        Path tmpDir = Files.createTempDirectory("appender-test");
        String socketPath = tmpDir.resolve("journal.sock").toString();

        Process server = new ProcessBuilder(
                "python3", "-c",
                "import socket,sys; s=socket.socket(socket.AF_UNIX,socket.SOCK_DGRAM);" +
                "s.bind('" + socketPath + "'); data,_=s.recvfrom(65536);" +
                "sys.stdout.buffer.write(data); s.close()")
                .redirectErrorStream(true)
                .start();

        long deadline = System.currentTimeMillis() + 2000;
        while (!Files.exists(Path.of(socketPath)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(Path.of(socketPath)), "Python server did not bind in time");

        var socket = new JournalSocket(socketPath);
        var layout = PatternLayout.newBuilder().withPattern("%m").build();
        var appender = new JournaldAppender(
                "test", null, layout, true, socket, "my-app", -1, "99",
                false, false, true, true, null, true, false, "THREAD_CONTEXT_", 65536);

        appender.start();

        var event = Log4jLogEvent.newBuilder()
                .setLoggerName("com.example.Service")
                .setLevel(Level.WARN)
                .setMessage(new SimpleMessage("something went wrong"))
                .setThreadName("worker-1")
                .build();

        appender.append(event);
        appender.stop();

        assertTrue(server.waitFor(2, TimeUnit.SECONDS), "Python server did not exit in time");
        String received = new String(server.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(received.contains("MESSAGE=something went wrong\n"));
        assertTrue(received.contains("PRIORITY=4\n"));
        assertTrue(received.contains("SYSLOG_IDENTIFIER=my-app\n"));
        assertTrue(received.contains("SYSLOG_PID=99\n"));
        assertTrue(received.contains("LOG4J_LOGGER=com.example.Service\n"));
        assertTrue(received.contains("THREAD_NAME=worker-1\n"));
        assertTrue(received.contains("LOG4J_APPENDER=test\n"));
    }

    @Test
    void builder_defaultsLayout_to_messageOnly() {
        var appender = JournaldAppender.newBuilder()
                .setName("test")
                .setSyslogIdentifier("app")
                .build();
        assertNotNull(appender);
        appender.stop();
    }

    @Test
    void pluginAnnotation_usesSystemdJournalName() {
        Plugin plugin = JournaldAppender.class.getAnnotation(Plugin.class);
        assertNotNull(plugin, "JournaldAppender must declare @Plugin");
        assertEquals("SystemdJournal", plugin.name());
    }
}
