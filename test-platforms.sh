#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-3-Clause
#
# Builds four Docker images that exercise the JournaldAppender:
#
#   log4j-journald:amd64-glibc  — linux/amd64  on Ubuntu  (azul/zulu-openjdk:<ver>-jre)
#   log4j-journald:amd64-musl   — linux/amd64  on Alpine  (azul/zulu-openjdk-alpine:<ver>-jre)
#   log4j-journald:arm64-glibc  — linux/arm64  on Ubuntu  (azul/zulu-openjdk:<ver>-jre)
#   log4j-journald:arm64-musl   — linux/arm64  on Alpine  (azul/zulu-openjdk-alpine:<ver>-jre)
#
# Default JDK version is 17 (JNI).  Use --jdk25 to switch to JDK 25 base
# images; the build will automatically include the FFM implementation via the
# java22-sources profile (activated when the build JDK is 22+).
#
# The images contain only a JRE.  Maven is NOT required inside the image;
# the appender JAR and its runtime dependencies are copied from the local
# Maven repository on this host.
#
# Prerequisites on the build host:
#   - Docker with buildx and QEMU support (for the arm64 images):
#       docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
#   - A JDK 17+ in PATH for the default build
#   - BUILD_JAVA_HOME pointing to a JDK 22+ installation when using --jdk25
#   - The project must have been built at least once: mvn package -DskipTests
#
# Usage:
#   ./test-platforms.sh [--skip-build] [--skip-arm64] [--jdk25] [--jni] [--cleanup]
#
#   --skip-build   Skip 'mvn package'; use an existing target/*.jar
#   --skip-arm64   Build only amd64 images (faster, no QEMU)
#   --jdk25        Use JDK 25 base images; build uses the modern JDK (activates FFM sources)
#   --jni          Show run commands with -Dorg.github.crac.systemd_appender.jni=true
#                  (forces JNI path even on JDK 22+ images; useful with --jdk25)
#   --cleanup      Remove all log4j-journald images and exit

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_SRC="$SCRIPT_DIR/src/test/docker"        # source files (Dockerfiles, SmokeTest.java, log4j2.xml)
DOCKER_CTX="$SCRIPT_DIR/target/docker-context"  # generated build context (JARs, compiled class)
LIB_DIR="$DOCKER_CTX/lib"
APPENDER_VER="1.0.0-SNAPSHOT"

# JDK used inside Docker images; also selects the Maven/javac build toolchain
JDK_VER=17

SKIP_BUILD=false
SKIP_ARM64=false
JNI_MODE=false
# BUILD_JAVA_HOME may be set in the environment; --jdk25 requires it

for arg in "$@"; do
    case "$arg" in
        --skip-build)  SKIP_BUILD=true  ;;
        --skip-arm64)  SKIP_ARM64=true  ;;
        --jni)         JNI_MODE=true    ;;
        --jdk25)
            JDK_VER=25
            if [[ -z "${BUILD_JAVA_HOME:-}" ]]; then
                echo "ERROR: --jdk25 requires BUILD_JAVA_HOME to be set to a JDK 22+ installation." >&2
                exit 1
            fi
            ;;
        --cleanup)
            echo "==> Removing log4j-journald images ..."
            docker images --filter "reference=log4j-journald" --format "{{.Repository}}:{{.Tag}}" \
                | xargs -r docker rmi
            exit 0
            ;;
        *) echo "Unknown option: $arg" >&2; exit 1 ;;
    esac
done

JAVAC="${BUILD_JAVA_HOME:+${BUILD_JAVA_HOME}/bin/}javac"
JAVAC_RELEASE=17

# ── 1. Build the appender JAR ────────────────────────────────────────────────

if [[ "$SKIP_BUILD" == false ]]; then
    echo "==> Building appender JAR${BUILD_JAVA_HOME:+ (JDK $JDK_VER, FFM sources included)}..."
    if [[ -n "${BUILD_JAVA_HOME:-}" ]]; then
        JAVA_HOME="$BUILD_JAVA_HOME" mvn -f "$SCRIPT_DIR/pom.xml" package -DskipTests -q
    else
        mvn -f "$SCRIPT_DIR/pom.xml" package -DskipTests -q
    fi
fi

APPENDER_JAR="$SCRIPT_DIR/target/log4j-systemd-appender-${APPENDER_VER}.jar"
if [[ ! -f "$APPENDER_JAR" ]]; then
    echo "ERROR: $APPENDER_JAR not found. Run without --skip-build or run 'mvn package -DskipTests' first." >&2
    exit 1
fi

# ── 2. Collect runtime JARs ──────────────────────────────────────────────────

echo "==> Collecting runtime JARs into $LIB_DIR ..."
rm -rf "$DOCKER_CTX"
mkdir -p "$LIB_DIR"

cp "$APPENDER_JAR" "$LIB_DIR/"

mvn -f "$SCRIPT_DIR/pom.xml" dependency:copy-dependencies \
    -DoutputDirectory="$LIB_DIR" \
    -DincludeScope=compile \
    -q

echo "   JARs collected: $(ls "$LIB_DIR" | tr '\n' ' ')"

# ── 3. Compile SmokeTest.java and stage static resources ────────────────────

echo "==> Compiling SmokeTest.java ..."
"$JAVAC" --release "$JAVAC_RELEASE" \
    -cp "$LIB_DIR/*" \
    -d "$DOCKER_CTX" \
    "$DOCKER_SRC/SmokeTest.java"

cp "$DOCKER_SRC/log4j2.xml" "$DOCKER_CTX/"

# ── 4. Ensure a multi-arch Docker builder is available ──────────────────────

BUILDER="log4j-journald-builder"

if ! docker buildx inspect "$BUILDER" &>/dev/null; then
    echo "==> Creating buildx builder '$BUILDER' with QEMU support ..."
    docker buildx create \
        --name "$BUILDER" \
        --platform linux/amd64,linux/arm64 \
        --driver docker-container \
        --use
else
    docker buildx use "$BUILDER"
fi

# Bootstrap (pulls the BuildKit image if needed)
docker buildx inspect --bootstrap "$BUILDER" > /dev/null

# ── 5. Build the four images ─────────────────────────────────────────────────

declare -A TARGETS
TARGETS["amd64-glibc"]="linux/amd64|Dockerfile.glibc"
TARGETS["amd64-musl"]="linux/amd64|Dockerfile.musl"
if [[ "$SKIP_ARM64" == false ]]; then
    TARGETS["arm64-glibc"]="linux/arm64|Dockerfile.glibc"
    TARGETS["arm64-musl"]="linux/arm64|Dockerfile.musl"
fi

for tag_suffix in "${!TARGETS[@]}"; do
    IFS='|' read -r platform dockerfile <<< "${TARGETS[$tag_suffix]}"
    tag="log4j-journald:${tag_suffix}"
    echo "==> Building $tag  (platform=$platform, jdk=$JDK_VER, dockerfile=$dockerfile) ..."
    docker buildx build \
        --builder "$BUILDER" \
        --platform "$platform" \
        --build-arg "JDK_VER=${JDK_VER}" \
        --load \
        -f "$DOCKER_SRC/$dockerfile" \
        -t "$tag" \
        "$DOCKER_CTX"
done

# Switch back to the default builder
docker buildx use default 2>/dev/null || true

# ── 6. Summary ───────────────────────────────────────────────────────────────

echo ""
echo "Built images (JDK ${JDK_VER}):"
docker images --filter "reference=log4j-journald" --format "  {{.Repository}}:{{.Tag}}  ({{.Size}})"

SOCKET_MOUNT="-v /run/systemd/journal/socket:/run/systemd/journal/socket"

echo ""
if [[ "$JNI_MODE" == true ]]; then
    echo "Run a smoke test (JNI mode; -D flag forces JNI path on JDK 22+):"
    for tag_suffix in "${!TARGETS[@]}"; do
        echo "  docker run --rm $SOCKET_MOUNT log4j-journald:${tag_suffix} \\"
        echo "    java --enable-native-access=ALL-UNNAMED -Dorg.github.crac.systemd_appender.jni=true -cp .:lib/* SmokeTest"
    done | sort
else
    echo "Run a smoke test (journal socket bind-mounted from host):"
    for tag_suffix in "${!TARGETS[@]}"; do
        echo "  docker run --rm $SOCKET_MOUNT log4j-journald:${tag_suffix}"
    done | sort
fi

echo ""
echo "Inspect results:"
echo "  journalctl -n 20 SYSLOG_IDENTIFIER=log4j-docker-smoke"
