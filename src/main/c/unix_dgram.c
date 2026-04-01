// SPDX-License-Identifier: BSD-3-Clause

// AI Tool Usage BOM
// ------------------
//
// AI Tools Used:
// - Anthropic Claude Sonnet 4.6
//

/*
 * Minimal JNI shim: open/sendto/close for AF_UNIX SOCK_DGRAM sockets.
 * No libsystemd dependency. All journal protocol logic lives in Java.
 *
 * JNI class: org.github.crac.systemd_appender.JniUnixDgramSocket
 */
#include <jni.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>

/*
 * Opens an unbound AF_UNIX SOCK_DGRAM socket.
 * Returns the file descriptor on success, or -errno on failure.
 */
JNIEXPORT jint JNICALL
Java_org_github_crac_systemd_1appender_JniUnixDgramSocket_open0(JNIEnv *env, jclass cls)
{
    int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        return -(jint)errno;
    }
    return (jint)fd;
}

/*
 * Sends data to a Unix domain socket at the given path using sendto(2).
 * Returns 0 on success, or -errno on failure.
 */
JNIEXPORT jint JNICALL
Java_org_github_crac_systemd_1appender_JniUnixDgramSocket_send0(
        JNIEnv *env, jclass cls, jint fd, jstring path, jbyteArray data, jint length)
{
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;

    const char *path_chars = (*env)->GetStringUTFChars(env, path, NULL);
    if (path_chars == NULL) {
        return -(jint)ENOMEM;
    }
    strncpy(addr.sun_path, path_chars, sizeof(addr.sun_path) - 1);
    (*env)->ReleaseStringUTFChars(env, path, path_chars);

    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (buf == NULL) {
        return -(jint)ENOMEM;
    }

    ssize_t result = sendto(fd, buf, (size_t)length, 0,
                            (struct sockaddr *)&addr, sizeof(addr));
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);

    if (result < 0) {
        return -(jint)errno;
    }
    return 0;
}

/*
 * Closes the socket file descriptor.
 */
JNIEXPORT void JNICALL
Java_org_github_crac_systemd_1appender_JniUnixDgramSocket_close0(
        JNIEnv *env, jclass cls, jint fd)
{
    close((int)fd);
}
