<!-- SPDX-License-Identifier: BSD-3-Clause -->
<!--
    AI Tool Usage BOM
    - - - - - - - - -

    AI Tools Used:
    - Anthropic Claude Sonnet 4.6
-->

# log4j-systemd-appender

A Log4j 2 appender that sends structured log events directly to the systemd journal
via Unix datagram socket, using the native [journal protocol].
No `libsystemd` dependency.

[journal protocol]: https://systemd.io/JOURNAL_NATIVE_PROTOCOL/

## Features

- Structured fields: `PRIORITY`, `SYSLOG_IDENTIFIER`, `THREAD_NAME`, `LOG4J_LOGGER`, `LOG4J_APPENDER`, `CODE_FILE/LINE/FUNC`, `STACKTRACE`, `SYSLOG_FACILITY`
- Optional ThreadContext (MDC) forwarding with configurable key prefix
- Message truncation when the datagram would exceed `maxMessageSize`
- On JDK 22+: uses the Foreign Function & Memory API — no native library loaded at runtime
- On JDK 17–21: uses a small JNI shim bundled for glibc and musl (Alpine Linux), x86_64 and aarch64

## Requirements

- Java 17 or later
- Linux with systemd (the journal socket at `/run/systemd/journal/socket`)

## Configuration

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration packages="org.github.crac.systemd_appender">
    <Appenders>
        <Systemd name="journal"
                 syslogIdentifier="my-app"
                 syslogFacility="3"
                 logSource="false"
                 logStacktrace="true"
                 logThreadName="true"
                 logLoggerName="true"
                 logLoggerAppName="MYAPP"
                 logAppenderName="true"
                 logThreadContext="true"
                 threadContextPrefix="THREAD_CONTEXT_"
                 maxMessageSize="65536"/>
    </Appenders>
    <Loggers>
        <Root level="info">
            <AppenderRef ref="journal"/>
        </Root>
    </Loggers>
</Configuration>
```

### Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `syslogIdentifier` | String | process name | Maps to `SYSLOG_IDENTIFIER`. Defaults to the executable name derived from `ProcessHandle`. |
| `syslogFacility` | int | *(unset)* | Maps to `SYSLOG_FACILITY`. Expects a numeric syslog facility code. Omitted when not set. |
| `logSource` | boolean | `false` | Log source location fields `CODE_FILE`, `CODE_LINE`, `CODE_FUNC`, `JAVA_CLASSNAME`. Has a performance cost; requires `includeLocation` on the logger. |
| `logStacktrace` | boolean | `true` | Log the full exception stacktrace to the `STACKTRACE` field. |
| `logThreadName` | boolean | `true` | Log the thread name to `THREAD_NAME`. |
| `logLoggerName` | boolean | `true` | Log the logger name. Field is `LOG4J_LOGGER` by default, or `{logLoggerAppName}_LOGGER` when `logLoggerAppName` is set. |
| `logLoggerAppName` | String | *(unset)* | When set, changes the logger-name field from `LOG4J_LOGGER` to `{logLoggerAppName}_LOGGER`. |
| `logAppenderName` | boolean | `true` | Log the appender name to `LOG4J_APPENDER`. |
| `logThreadContext` | boolean | `true` | Forward ThreadContext (MDC) entries as `THREAD_CONTEXT_*` fields. Keys are uppercased and non-alphanumeric characters replaced with `_`. |
| `threadContextPrefix` | String | `THREAD_CONTEXT_` | Prefix applied to ThreadContext keys. |
| `maxMessageSize` | int | `65536` | Maximum datagram size in bytes. Messages that would exceed this limit are truncated and suffixed with `[TRUNCATED]`. |

## System properties

| Property | Description |
|---|---|
| `org.github.crac.systemd_appender.jni=true` | Force the JNI implementation even on JDK 22+. Useful for verifying that the bundled native library works on a modern JDK. |

## Building

A standard `mvn package` compiles the Java sources and the native JNI shim.
The native library is compiled for four targets; each compiler is optional — if a
compiler is not found the corresponding native variant is silently skipped.

### Compiler requirements

| Target | Compiler | Debian/Ubuntu | Fedora/RHEL |
|---|---|---|---|
| Linux x86_64 (glibc) | `gcc` | `apt-get install gcc` | `dnf install gcc` |
| Linux aarch64 (glibc) | `aarch64-linux-gnu-gcc` | `apt-get install gcc-aarch64-linux-gnu` | `dnf install gcc-aarch64-linux-gnu` |
| Linux x86_64 (musl) | `musl-gcc` | `apt-get install musl-tools` | `dnf install musl-gcc` |
| Linux aarch64 (musl) | `aarch64-linux-musl-gcc` | *(not in standard repos — see below)* | *(not in standard repos — see below)* |

#### aarch64 musl cross-compiler

There is no standard distribution package for an aarch64-musl cross-compiler.
Pre-built toolchains are available from [musl.cc]:

```sh
# Example: download and install to /opt/musl-cross
wget https://musl.cc/aarch64-linux-musl-cross.tgz
tar -xf aarch64-linux-musl-cross.tgz -C /opt
export PATH="/opt/aarch64-linux-musl-cross/bin:$PATH"
```

[musl.cc]: https://musl.cc

### Install all compilers (Debian/Ubuntu)

```sh
apt-get install gcc gcc-aarch64-linux-gnu musl-tools
# Plus aarch64 musl cross-compiler from musl.cc if needed
```

### Install all compilers (Fedora/RHEL)

```sh
dnf install gcc gcc-aarch64-linux-gnu musl-gcc
# Plus aarch64 musl cross-compiler from musl.cc if needed
```
