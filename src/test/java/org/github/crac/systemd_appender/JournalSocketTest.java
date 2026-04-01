// SPDX-License-Identifier: BSD-3-Clause

// AI Tool Usage BOM
// ------------------
//
// AI Tools Used:
// - Anthropic Claude Sonnet 4.6
//

package org.github.crac.systemd_appender;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JournalSocketTest {

    // ---- field encoding ----

    @Test
    void appendField_simpleValue_usesEqualsFormat() throws IOException {
        var out = new ByteArrayOutputStream();
        JournaldAppender.appendField(out, "MESSAGE", "hello world");
        assertEquals("MESSAGE=hello world\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void appendField_valueWithNewline_usesBinaryFormat() throws IOException {
        var out = new ByteArrayOutputStream();
        JournaldAppender.appendField(out, "MESSAGE", "line1\nline2");

        byte[] result = out.toByteArray();
        byte[] keyBytes = "MESSAGE".getBytes(StandardCharsets.UTF_8);
        byte[] valBytes = "line1\nline2".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(keyBytes, java.util.Arrays.copyOfRange(result, 0, keyBytes.length));
        assertEquals('\n', result[keyBytes.length]);

        long encodedLen = ByteBuffer.wrap(result, keyBytes.length + 1, 8)
                .order(ByteOrder.LITTLE_ENDIAN).getLong();
        assertEquals(valBytes.length, encodedLen);

        int valStart = keyBytes.length + 1 + 8;
        assertArrayEquals(valBytes, java.util.Arrays.copyOfRange(result, valStart, valStart + valBytes.length));
        assertEquals('\n', result[valStart + valBytes.length]);
    }

    // ---- level mapping ----

    @Test
    void toSyslogPriority_mapsAllLevels() {
        assertEquals(2, JournaldAppender.toSyslogPriority(org.apache.logging.log4j.Level.FATAL));
        assertEquals(3, JournaldAppender.toSyslogPriority(org.apache.logging.log4j.Level.ERROR));
        assertEquals(4, JournaldAppender.toSyslogPriority(org.apache.logging.log4j.Level.WARN));
        assertEquals(6, JournaldAppender.toSyslogPriority(org.apache.logging.log4j.Level.INFO));
        assertEquals(7, JournaldAppender.toSyslogPriority(org.apache.logging.log4j.Level.DEBUG));
        assertEquals(7, JournaldAppender.toSyslogPriority(org.apache.logging.log4j.Level.TRACE));
    }

    // ---- event encoding ----

    @Test
    void encodeEvent_containsAllExpectedFields() {
        var event = org.apache.logging.log4j.core.impl.Log4jLogEvent.newBuilder()
                .setLoggerName("com.example.Foo")
                .setLevel(org.apache.logging.log4j.Level.INFO)
                .setMessage(new org.apache.logging.log4j.message.SimpleMessage("hello"))
                .setThreadName("main-thread")
                .build();
        var layout = org.apache.logging.log4j.core.layout.PatternLayout.newBuilder()
                .withPattern("%m").build();

        byte[] encoded = JournaldAppender.encodeEvent(event, layout, "my-app", -1, "42",
                false, true, true, true, null, true, "test-appender", false, "THREAD_CONTEXT_", 65536);
        String result = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(result.contains("MESSAGE=hello\n"), "missing MESSAGE field");
        assertTrue(result.contains("PRIORITY=6\n"), "missing PRIORITY field");
        assertTrue(result.contains("SYSLOG_IDENTIFIER=my-app\n"), "missing SYSLOG_IDENTIFIER");
        assertTrue(result.contains("SYSLOG_PID=42\n"), "missing SYSLOG_PID");
        assertTrue(result.contains("THREAD_NAME=main-thread\n"), "missing THREAD_NAME");
        assertTrue(result.contains("LOG4J_LOGGER=com.example.Foo\n"), "missing LOG4J_LOGGER");
        assertTrue(result.contains("LOG4J_APPENDER=test-appender\n"), "missing LOG4J_APPENDER");
    }

    @Test
    void encodeEvent_syslogFacility_includedWhenSet() {
        var event = org.apache.logging.log4j.core.impl.Log4jLogEvent.newBuilder()
                .setLoggerName("test")
                .setLevel(org.apache.logging.log4j.Level.INFO)
                .setMessage(new org.apache.logging.log4j.message.SimpleMessage("msg"))
                .setThreadName("t")
                .build();
        var layout = org.apache.logging.log4j.core.layout.PatternLayout.newBuilder()
                .withPattern("%m").build();

        byte[] encoded = JournaldAppender.encodeEvent(event, layout, "app", 16, "1",
                false, false, false, false, null, false, "a", false, "THREAD_CONTEXT_", 65536);
        String result = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(result.contains("SYSLOG_FACILITY=16\n"), "missing SYSLOG_FACILITY");
        assertFalse(result.contains("THREAD_NAME="), "THREAD_NAME must be absent when logThreadName=false");
        assertFalse(result.contains("LOG4J_LOGGER="), "LOG4J_LOGGER must be absent when logLoggerName=false");
        assertFalse(result.contains("LOG4J_APPENDER="), "LOG4J_APPENDER must be absent when logAppenderName=false");
    }

    @Test
    void encodeEvent_logStacktrace_addsStacktraceField() {
        var thrown = new RuntimeException("boom");
        var event = org.apache.logging.log4j.core.impl.Log4jLogEvent.newBuilder()
                .setLoggerName("test")
                .setLevel(org.apache.logging.log4j.Level.ERROR)
                .setMessage(new org.apache.logging.log4j.message.SimpleMessage("error"))
                .setThreadName("t")
                .setThrown(thrown)
                .build();
        var layout = org.apache.logging.log4j.core.layout.PatternLayout.newBuilder()
                .withPattern("%m").build();

        byte[] encoded = JournaldAppender.encodeEvent(event, layout, "app", -1, "1",
                false, true, false, false, null, false, "a", false, "THREAD_CONTEXT_", 65536);
        String result = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(result.contains("STACKTRACE=") || result.contains("STACKTRACE\n"),
                "missing STACKTRACE field");
        assertTrue(result.contains("RuntimeException"), "stacktrace must contain exception class");
    }

    @Test
    void encodeEvent_logThreadContext_addsNormalizedFields() {
        var event = org.apache.logging.log4j.core.impl.Log4jLogEvent.newBuilder()
                .setLoggerName("test")
                .setLevel(org.apache.logging.log4j.Level.INFO)
                .setMessage(new org.apache.logging.log4j.message.SimpleMessage("msg"))
                .setThreadName("t")
                .setContextMap(java.util.Map.of("my-key", "val1", "OTHER", "val2"))
                .build();
        var layout = org.apache.logging.log4j.core.layout.PatternLayout.newBuilder()
                .withPattern("%m").build();

        byte[] encoded = JournaldAppender.encodeEvent(event, layout, "app", -1, "1",
                false, false, false, false, null, false, "a", true, "THREAD_CONTEXT_", 65536);
        String result = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(result.contains("THREAD_CONTEXT_MY_KEY=val1\n"), "missing normalized MDC field");
        assertTrue(result.contains("THREAD_CONTEXT_OTHER=val2\n"), "missing MDC field");
    }

    @Test
    void encodeEvent_truncatesLargeMessage() {
        String longMessage = "x".repeat(200);
        var event = org.apache.logging.log4j.core.impl.Log4jLogEvent.newBuilder()
                .setLoggerName("test")
                .setLevel(org.apache.logging.log4j.Level.INFO)
                .setMessage(new org.apache.logging.log4j.message.SimpleMessage(longMessage))
                .setThreadName("t")
                .build();
        var layout = org.apache.logging.log4j.core.layout.PatternLayout.newBuilder()
                .withPattern("%m").build();

        byte[] encoded = JournaldAppender.encodeEvent(event, layout, "app", -1, "1",
                false, false, false, false, null, false, "a", false, "THREAD_CONTEXT_", 100);
        String result = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(result.contains("[TRUNCATED]"), "missing [TRUNCATED] marker");
        assertFalse(result.contains(longMessage), "full message must not appear");
        assertTrue(encoded.length <= 100, "encoded size must not exceed maxMessageSize");
    }

    @Test
    void encodeEvent_logLoggerAppName_usesCustomFieldName() {
        var event = org.apache.logging.log4j.core.impl.Log4jLogEvent.newBuilder()
                .setLoggerName("com.example.Foo")
                .setLevel(org.apache.logging.log4j.Level.INFO)
                .setMessage(new org.apache.logging.log4j.message.SimpleMessage("msg"))
                .setThreadName("t")
                .build();
        var layout = org.apache.logging.log4j.core.layout.PatternLayout.newBuilder()
                .withPattern("%m").build();

        byte[] encoded = JournaldAppender.encodeEvent(event, layout, "app", -1, "1",
                false, false, false, true, "MYAPP", false, "a", false, "THREAD_CONTEXT_", 65536);
        String result = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(result.contains("MYAPP_LOGGER=com.example.Foo\n"), "missing custom logger field");
        assertFalse(result.contains("LOG4J_LOGGER="), "default field must not appear when logLoggerAppName is set");
    }

    @Test
    void encodeEvent_logSource_addsCodeFields() {
        var src = new StackTraceElement("com.example.Foo", "doWork", "Foo.java", 42);
        var event = org.apache.logging.log4j.core.impl.Log4jLogEvent.newBuilder()
                .setLoggerName("test")
                .setLevel(org.apache.logging.log4j.Level.DEBUG)
                .setMessage(new org.apache.logging.log4j.message.SimpleMessage("msg"))
                .setThreadName("t")
                .setSource(src)
                .build();
        var layout = org.apache.logging.log4j.core.layout.PatternLayout.newBuilder()
                .withPattern("%m").build();

        byte[] encoded = JournaldAppender.encodeEvent(event, layout, "app", -1, "1",
                true, false, false, false, null, false, "a", false, "THREAD_CONTEXT_", 65536);
        String result = new String(encoded, StandardCharsets.UTF_8);

        assertTrue(result.contains("CODE_FILE=Foo.java\n"), "missing CODE_FILE");
        assertTrue(result.contains("CODE_LINE=42\n"), "missing CODE_LINE");
        assertTrue(result.contains("CODE_FUNC=doWork\n"), "missing CODE_FUNC");
        assertTrue(result.contains("JAVA_CLASSNAME=com.example.Foo\n"), "missing JAVA_CLASSNAME");
    }

    // ---- socket send (requires native library + Python 3) ----

    /**
     * Starts a Python 3 Unix DGRAM server, sends one datagram via UnixDgramSocket,
     * and verifies the received bytes match what was sent.
     */
    @Test
    void send_deliversDatagramToServer() throws Exception {
        Path tmpDir = Files.createTempDirectory("journal-test");
        String socketPath = tmpDir.resolve("journal.sock").toString();

        // Python: bind, receive one datagram, write it to stdout, exit
        Process server = new ProcessBuilder(
                "python3", "-c",
                "import socket,sys; s=socket.socket(socket.AF_UNIX,socket.SOCK_DGRAM);" +
                "s.bind('" + socketPath + "'); data,_=s.recvfrom(4096);" +
                "sys.stdout.buffer.write(data); s.close()")
                .redirectErrorStream(true)
                .start();

        // Wait for the socket file to appear (server has bound)
        long deadline = System.currentTimeMillis() + 2000;
        while (!Files.exists(Path.of(socketPath)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(Path.of(socketPath)), "Python server did not bind in time");

        byte[] data = "MESSAGE=hello\n".getBytes(StandardCharsets.UTF_8);
        try (var socket = new UnixDgramSocket()) {
            socket.send(socketPath, data);
        }

        assertTrue(server.waitFor(2, TimeUnit.SECONDS), "Python server did not exit in time");
        assertArrayEquals(data, server.getInputStream().readAllBytes());
    }

    @Test
    void send_missingSocketPath_doesNotThrow() {
        var js = new JournalSocket("/nonexistent/path/journal.sock");
        assertDoesNotThrow(() -> js.send("MESSAGE=test\n".getBytes(StandardCharsets.UTF_8)));
        js.close();
    }
}
