// SPDX-License-Identifier: BSD-3-Clause

// AI Tool Usage BOM
// ------------------
//
// AI Tools Used:
// - Anthropic Claude Sonnet 4.6
//

package org.github.crac.systemd_appender;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Foreign Function &amp; Memory (FFM) implementation of an AF_UNIX SOCK_DGRAM
 * socket.  Calls libc {@code socket(2)}, {@code sendto(2)}, and {@code close(2)}
 * directly — no JNI shim or bundled native library needed.
 *
 * <p>Requires JDK 22+.  Selected automatically on JDK 22+ via the Multi-Release
 * JAR; use {@link UnixDgramSocket} (the selector) to instantiate.
 */
final class FfmUnixDgramSocket implements UnixDgramSocket {

    // Linux ABI constants
    private static final int AF_UNIX      = 1;
    private static final int SOCK_DGRAM   = 2;
    private static final int SOCK_CLOEXEC = 0x80000;

    // struct sockaddr_un layout (Linux): sa_family_t (2 bytes) + sun_path[108]
    private static final int SOCKADDR_UN_SIZE  = 110;
    private static final int SUN_FAMILY_OFFSET = 0;
    private static final int SUN_PATH_OFFSET   = 2;
    private static final int SUN_PATH_MAX      = 107; // leave one byte for NUL terminator

    private static final StructLayout capturedState;
    private static final long         errnoOffset;
    private static final MethodHandle socket;
    private static final MethodHandle sendto;
    private static final MethodHandle close;

    static {
        Linker        linker       = Linker.nativeLinker();
        SymbolLookup  libc         = linker.defaultLookup();
        Linker.Option captureErrno = Linker.Option.captureCallState("errno");

        capturedState = Linker.Option.captureStateLayout();
        errnoOffset   = capturedState.byteOffset(
                MemoryLayout.PathElement.groupElement("errno"));

        // int socket(int domain, int type, int protocol)
        socket = linker.downcallHandle(
                libc.find("socket").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                captureErrno);

        // ssize_t sendto(int sockfd, const void *buf, size_t len, int flags,
        //                const struct sockaddr *dest_addr, socklen_t addrlen)
        sendto = linker.downcallHandle(
                libc.find("sendto").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                captureErrno);

        // int close(int fd)
        close = linker.downcallHandle(
                libc.find("close").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    private final int fd;

    FfmUnixDgramSocket() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment captured = arena.allocate(capturedState);
            int result = (int) socket.invoke(captured, AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
            if (result < 0) {
                int errno = captured.get(ValueLayout.JAVA_INT, errnoOffset);
                throw new IOException("socket(AF_UNIX, SOCK_DGRAM) failed: errno " + errno);
            }
            this.fd = result;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("socket() call failed", t);
        }
    }

    public void send(String path, byte[] data) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment addr = arena.allocate(SOCKADDR_UN_SIZE);
            addr.set(ValueLayout.JAVA_SHORT, SUN_FAMILY_OFFSET, (short) AF_UNIX);
            byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
            int pathLen = Math.min(pathBytes.length, SUN_PATH_MAX);
            addr.asSlice(SUN_PATH_OFFSET, pathLen)
                    .copyFrom(MemorySegment.ofArray(pathBytes).asSlice(0, pathLen));

            MemorySegment buf = arena.allocate(data.length);
            buf.copyFrom(MemorySegment.ofArray(data));

            MemorySegment captured = arena.allocate(capturedState);
            long result = (long) sendto.invoke(
                    captured, fd, buf, (long) data.length, 0, addr, SOCKADDR_UN_SIZE);
            if (result < 0) {
                int errno = captured.get(ValueLayout.JAVA_INT, errnoOffset);
                throw new IOException("sendto(" + path + ") failed: errno " + errno);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("sendto() call failed", t);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            close.invoke(fd);
        } catch (Throwable t) {
            throw new IOException("close() call failed", t);
        }
    }
}
