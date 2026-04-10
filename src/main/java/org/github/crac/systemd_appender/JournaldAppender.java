// SPDX-License-Identifier: BSD-3-Clause

// AI Tool Usage BOM
// ------------------
//
// AI Tools Used:
// - Anthropic Claude Sonnet 4.6
//

package org.github.crac.systemd_appender;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

@Plugin(name = "SystemdJournal", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE, printObject = true)
public final class JournaldAppender extends AbstractAppender implements org.crac.Resource {

    private static final byte[] TRUNCATED_MARKER =
            "[TRUNCATED]".getBytes(StandardCharsets.UTF_8);

    private final JournalSocket socket;
    private final String syslogIdentifier;
    private final int syslogFacility;
    private String pid;
    private final boolean logSource;
    private final boolean logStacktrace;
    private final boolean logThreadName;
    private final boolean logLoggerName;
    private final String logLoggerAppName;
    private final boolean logAppenderName;
    private final boolean logThreadContext;
    private final String threadContextPrefix;
    private final int maxMessageSize;

    JournaldAppender(String name, Filter filter, Layout<?> layout, boolean ignoreExceptions,
                     JournalSocket socket, String syslogIdentifier, int syslogFacility, String pid,
                     boolean logSource, boolean logStacktrace, boolean logThreadName,
                     boolean logLoggerName, String logLoggerAppName, boolean logAppenderName,
                     boolean logThreadContext, String threadContextPrefix, int maxMessageSize) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
        this.socket = socket;
        this.syslogIdentifier = syslogIdentifier;
        this.syslogFacility = syslogFacility;
        this.pid = pid;
        this.logSource = logSource;
        this.logStacktrace = logStacktrace;
        this.logThreadName = logThreadName;
        this.logLoggerName = logLoggerName;
        this.logLoggerAppName = logLoggerAppName;
        this.logAppenderName = logAppenderName;
        this.logThreadContext = logThreadContext;
        this.threadContextPrefix = threadContextPrefix;
        this.maxMessageSize = maxMessageSize;
        org.crac.Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(org.crac.Context<? extends org.crac.Resource> context) {
        socket.close();
        NativeLoader.deleteTempLibrary();
    }

    @Override
    public void afterRestore(org.crac.Context<? extends org.crac.Resource> context) {
        pid = String.valueOf(ProcessHandle.current().pid());
        socket.afterRestore();
    }

    @Override
    public void append(LogEvent event) {
        byte[] data = encodeEvent(
                event, getLayout(), syslogIdentifier, syslogFacility, pid,
                logSource, logStacktrace, logThreadName, logLoggerName, logLoggerAppName,
                logAppenderName, getName(), logThreadContext, threadContextPrefix,
                maxMessageSize);
        socket.send(data);
    }

    @Override
    public boolean requiresLocation() {
        return logSource;
    }

    @Override
    public void stop() {
        super.stop();
        socket.close();
    }

    // ---- encoding ----

    static byte[] encodeEvent(LogEvent event, Layout<?> layout, String syslogId,
                               int syslogFacility, String pid,
                               boolean logSource, boolean logStacktrace,
                               boolean logThreadName, boolean logLoggerName, String logLoggerAppName,
                               boolean logAppenderName, String appenderName,
                               boolean logThreadContext, String threadContextPrefix,
                               int maxMessageSize) {
        var other = new ByteArrayOutputStream(512);
        try {
            appendField(other, "PRIORITY", String.valueOf(toSyslogPriority(event.getLevel())));
            appendField(other, "SYSLOG_IDENTIFIER", syslogId);
            if (syslogFacility >= 0) {
                appendField(other, "SYSLOG_FACILITY", String.valueOf(syslogFacility));
            }
            appendField(other, "SYSLOG_PID", pid);

            if (logThreadName) {
                appendField(other, "THREAD_NAME", event.getThreadName());
            }
            if (logLoggerName) {
                String loggerFieldName = (logLoggerAppName != null && !logLoggerAppName.isEmpty())
                        ? logLoggerAppName + "_LOGGER"
                        : "LOG4J_LOGGER";
                appendField(other, loggerFieldName, event.getLoggerName());
            }
            if (logAppenderName) {
                appendField(other, "LOG4J_APPENDER", appenderName);
            }

            if (logSource) {
                StackTraceElement src = event.getSource();
                if (src != null) {
                    String file = src.getFileName();
                    if (file != null && !file.equals("?")) {
                        appendField(other, "CODE_FILE", file);
                    }
                    int line = src.getLineNumber();
                    if (line > 0) {
                        appendField(other, "CODE_LINE", String.valueOf(line));
                    }
                    String method = src.getMethodName();
                    if (method != null && !method.equals("?")) {
                        appendField(other, "CODE_FUNC", method);
                    }
                    String cls = src.getClassName();
                    if (cls != null && !cls.equals("?")) {
                        appendField(other, "JAVA_CLASSNAME", cls);
                    }
                }
            }

            if (logStacktrace && event.getThrown() != null) {
                appendField(other, "STACKTRACE", formatThrowable(event.getThrown()));
            }

            if (logThreadContext) {
                var contextData = event.getContextData();
                if (contextData != null) {
                    contextData.forEach((k, v) -> {
                        if (v != null) {
                            try {
                                appendField(other, normalizeContextKey(threadContextPrefix, k), v.toString());
                            } catch (IOException e) {
                                throw new AssertionError("ByteArrayOutputStream must not throw", e);
                            }
                        }
                    });
                }
            }
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream must not throw", e);
        }

        String message = new String(layout.toByteArray(event), StandardCharsets.UTF_8);
        // Worst-case binary MESSAGE header: "MESSAGE\n" (8 bytes) + 8-byte LE length + trailing \n = 17
        int available = maxMessageSize - other.size() - 17;
        if (available < 0) {
            available = 0;
        }
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        if (msgBytes.length > available) {
            int keepBytes = Math.max(0, available - TRUNCATED_MARKER.length);
            if (keepBytes <= 0) {
                message = "[TRUNCATED]";
            } else {
                int i = keepBytes;
                while (i > 0 && (msgBytes[i] & 0xC0) == 0x80) {
                    i--;
                }
                message = new String(msgBytes, 0, i, StandardCharsets.UTF_8) + "[TRUNCATED]";
            }
        }

        var buf = new ByteArrayOutputStream(other.size() + message.length() + 32);
        try {
            appendField(buf, "MESSAGE", message);
            other.writeTo(buf);
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream must not throw", e);
        }
        return buf.toByteArray();
    }

    static int toSyslogPriority(Level level) {
        int i = level.intLevel();
        if (i <= 100) { // FATAL  (intLevel=100)
            return 2;
        }
        if (i <= 200) { // ERROR  (intLevel=200)
            return 3;
        }
        if (i <= 300) { // WARN   (intLevel=300)
            return 4;
        }
        if (i <= 400) { // INFO   (intLevel=400)
            return 6;
        }
        return 7; // DEBUG/TRACE (intLevel=500/600)
    }

    static void appendField(ByteArrayOutputStream out, String key, String value) throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valBytes = value.getBytes(StandardCharsets.UTF_8);
        if (containsNewline(valBytes)) {
            out.write(keyBytes);
            out.write('\n');
            long len = valBytes.length;
            for (int i = 0; i < 8; i++) {
                out.write((int) (len & 0xFF));
                len >>= 8;
            }
            out.write(valBytes);
            out.write('\n');
        } else {
            out.write(keyBytes);
            out.write('=');
            out.write(valBytes);
            out.write('\n');
        }
    }

    private static String formatThrowable(Throwable t) {
        var sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String normalizeContextKey(String prefix, String key) {
        String upper = key.toUpperCase(Locale.ROOT);
        var sb = new StringBuilder(prefix.length() + upper.length());
        sb.append(prefix);
        for (char c : upper.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        return sb.toString();
    }

    private static boolean containsNewline(byte[] bytes) {
        for (byte b : bytes) {
            if (b == '\n') {
                return true;
            }
        }
        return false;
    }

    // ---- builder ----

    @PluginBuilderFactory
    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder extends AbstractAppender.Builder<Builder>
            implements org.apache.logging.log4j.core.util.Builder<JournaldAppender> {

        @PluginBuilderAttribute
        private String syslogIdentifier;

        @PluginBuilderAttribute
        private int syslogFacility = -1;

        @PluginBuilderAttribute
        private int maxMessageSize = 65536;

        @PluginBuilderAttribute
        private boolean logSource = false;

        @PluginBuilderAttribute
        private boolean logStacktrace = true;

        @PluginBuilderAttribute
        private boolean logThreadName = true;

        @PluginBuilderAttribute
        private boolean logLoggerName = true;

        @PluginBuilderAttribute
        private String logLoggerAppName;

        @PluginBuilderAttribute
        private boolean logAppenderName = true;

        @PluginBuilderAttribute
        private boolean logThreadContext = true;

        @PluginBuilderAttribute
        private String threadContextPrefix = "THREAD_CONTEXT_";

        public Builder setSyslogIdentifier(String syslogIdentifier) {
            this.syslogIdentifier = syslogIdentifier;
            return this;
        }

        public Builder setSyslogFacility(int syslogFacility) {
            this.syslogFacility = syslogFacility;
            return this;
        }

        public Builder setMaxMessageSize(int maxMessageSize) {
            this.maxMessageSize = maxMessageSize;
            return this;
        }

        public Builder setLogSource(boolean logSource) {
            this.logSource = logSource;
            return this;
        }

        public Builder setLogStacktrace(boolean logStacktrace) {
            this.logStacktrace = logStacktrace;
            return this;
        }

        public Builder setLogThreadName(boolean logThreadName) {
            this.logThreadName = logThreadName;
            return this;
        }

        public Builder setLogLoggerName(boolean logLoggerName) {
            this.logLoggerName = logLoggerName;
            return this;
        }

        public Builder setLogLoggerAppName(String logLoggerAppName) {
            this.logLoggerAppName = logLoggerAppName;
            return this;
        }

        public Builder setLogAppenderName(boolean logAppenderName) {
            this.logAppenderName = logAppenderName;
            return this;
        }

        public Builder setLogThreadContext(boolean logThreadContext) {
            this.logThreadContext = logThreadContext;
            return this;
        }

        public Builder setThreadContextPrefix(String threadContextPrefix) {
            this.threadContextPrefix = threadContextPrefix;
            return this;
        }

        @Override
        public JournaldAppender build() {
            String id = (syslogIdentifier != null && !syslogIdentifier.isEmpty())
                    ? syslogIdentifier : resolveProcessName();
            Layout<?> layout = getLayout();
            if (layout == null) {
                layout = PatternLayout.newBuilder().withPattern("%m").build();
            }
            String pid = String.valueOf(ProcessHandle.current().pid());
            return new JournaldAppender(
                    getName(), getFilter(), layout, isIgnoreExceptions(),
                    new JournalSocket(), id, syslogFacility, pid,
                    logSource, logStacktrace, logThreadName, logLoggerName, logLoggerAppName,
                    logAppenderName, logThreadContext, threadContextPrefix, maxMessageSize);
        }

        private static String resolveProcessName() {
            return ProcessHandle.current().info().command()
                    .map(cmd -> Path.of(cmd).getFileName().toString())
                    .orElse("java");
        }
    }
}
