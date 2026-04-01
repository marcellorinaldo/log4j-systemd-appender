// SPDX-License-Identifier: BSD-3-Clause

// AI Tool Usage BOM
// ------------------
//
// AI Tools Used:
// - Anthropic Claude Sonnet 4.6
//

package org.github.crac.systemd_appender;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

final class NativeLoader {

    private static Path tempLibraryPath;

    private NativeLoader() {}

    /**
     * Extracts the named shared library from the JAR resources and loads it via
     * {@link System#load}. The resource path is
     * {@code /native/linux-<arch>[-musl]/lib<name>.so}, where the {@code -musl}
     * suffix is added on musl-based systems (e.g. Alpine Linux).
     *
     * @throws UnsatisfiedLinkError if the library cannot be found or loaded
     */
    static synchronized void loadLibrary(String name) {
        if (tempLibraryPath != null) {
            return;
        }
        String arch = normalizeArch(System.getProperty("os.arch", ""));
        String variant = isMusl(arch) ? "linux-" + arch + "-musl" : "linux-" + arch;
        String resource = "/native/" + variant + "/lib" + name + ".so";

        InputStream in = NativeLoader.class.getResourceAsStream(resource);
        if (in == null) {
            throw new UnsatisfiedLinkError(
                    "Native library not bundled in JAR for this platform: " + resource);
        }

        try (in) {
            Path tmp = Files.createTempFile("lib" + name + "-", ".so");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().setExecutable(true);
            tempLibraryPath = tmp;
            System.load(tmp.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new UnsatisfiedLinkError(
                    "Failed to extract and load native library " + resource + ": " + e.getMessage());
        }
    }

    /**
     * Deletes the temporary shared library file extracted during {@link #loadLibrary}.
     * Called on CRaC checkpoint so no temp file survives in the checkpoint image.
     */
    static synchronized void deleteTempLibrary() {
        Path path = tempLibraryPath;
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
            tempLibraryPath = null;
        }
    }

    /**
     * Detects whether the running process uses musl libc by checking whether
     * the musl dynamic linker is mapped into the process (via /proc/self/maps).
     * This is reliable even on systems where musl is installed alongside glibc
     * (e.g. Ubuntu with musl-tools), because the file's presence on disk is not
     * sufficient — only the active dynamic linker appears in the process maps.
     */
    private static boolean isMusl(String arch) {
        try {
            return Files.lines(Path.of("/proc/self/maps"))
                    .anyMatch(line -> line.contains("ld-musl-"));
        } catch (IOException e) {
            return false;
        }
    }

    private static String normalizeArch(String osArch) {
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64"  -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new UnsatisfiedLinkError("Unsupported architecture: " + osArch);
        };
    }
}
