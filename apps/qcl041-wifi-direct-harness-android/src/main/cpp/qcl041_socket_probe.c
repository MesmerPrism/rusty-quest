#include <jni.h>

#include <android/multinetwork.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

static long long qcl041_now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return ((long long) ts.tv_sec * 1000LL) + ((long long) ts.tv_nsec / 1000000LL);
}

static void qcl041_copy_jstring(JNIEnv* env, jstring value, char* out, size_t out_len) {
    if (out_len == 0) {
        return;
    }
    out[0] = '\0';
    if (value == NULL) {
        return;
    }
    const char* raw = (*env)->GetStringUTFChars(env, value, NULL);
    if (raw == NULL) {
        return;
    }
    snprintf(out, out_len, "%s", raw);
    (*env)->ReleaseStringUTFChars(env, value, raw);
}

static void qcl041_json_escape(const char* raw, char* out, size_t out_len) {
    if (out_len == 0) {
        return;
    }
    size_t write_index = 0;
    out[0] = '\0';
    if (raw == NULL) {
        return;
    }
    for (size_t read_index = 0; raw[read_index] != '\0' && write_index + 1 < out_len; read_index++) {
        unsigned char c = (unsigned char) raw[read_index];
        if ((c == '"' || c == '\\') && write_index + 2 < out_len) {
            out[write_index++] = '\\';
            out[write_index++] = (char) c;
        } else if (c == '\n' && write_index + 2 < out_len) {
            out[write_index++] = '\\';
            out[write_index++] = 'n';
        } else if (c == '\r' && write_index + 2 < out_len) {
            out[write_index++] = '\\';
            out[write_index++] = 'r';
        } else if (c == '\t' && write_index + 2 < out_len) {
            out[write_index++] = '\\';
            out[write_index++] = 't';
        } else if (c >= 0x20) {
            out[write_index++] = (char) c;
        }
    }
    out[write_index] = '\0';
}

static bool qcl041_parse_ipv4_target(const char* address, int port, struct sockaddr_in* out) {
    if (address == NULL || out == NULL || port <= 0 || port > 65535) {
        return false;
    }
    memset(out, 0, sizeof(*out));
    out->sin_family = AF_INET;
    out->sin_port = htons((uint16_t) port);
    return inet_pton(AF_INET, address, &out->sin_addr) == 1;
}

static void qcl041_socket_name(int fd, char* out, size_t out_len) {
    if (out_len == 0) {
        return;
    }
    out[0] = '\0';
    struct sockaddr_in local_addr;
    socklen_t local_len = sizeof(local_addr);
    if (getsockname(fd, (struct sockaddr*) &local_addr, &local_len) != 0) {
        return;
    }
    char address[INET_ADDRSTRLEN] = {0};
    if (inet_ntop(AF_INET, &local_addr.sin_addr, address, sizeof(address)) == NULL) {
        return;
    }
    snprintf(out, out_len, "%s:%u", address, (unsigned int) ntohs(local_addr.sin_port));
}

static int qcl041_wait_for_connect(int fd, int timeout_ms) {
    fd_set write_set;
    FD_ZERO(&write_set);
    FD_SET(fd, &write_set);
    struct timeval timeout;
    timeout.tv_sec = timeout_ms / 1000;
    timeout.tv_usec = (timeout_ms % 1000) * 1000;
    int selected = select(fd + 1, NULL, &write_set, NULL, &timeout);
    if (selected <= 0) {
        return selected == 0 ? ETIMEDOUT : errno;
    }
    int socket_error = 0;
    socklen_t error_len = sizeof(socket_error);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &socket_error, &error_len) != 0) {
        return errno;
    }
    return socket_error;
}

static int qcl041_wait_for_read(int fd, int timeout_ms) {
    fd_set read_set;
    FD_ZERO(&read_set);
    FD_SET(fd, &read_set);
    struct timeval timeout;
    timeout.tv_sec = timeout_ms / 1000;
    timeout.tv_usec = (timeout_ms % 1000) * 1000;
    int selected = select(fd + 1, &read_set, NULL, NULL, &timeout);
    if (selected <= 0) {
        return selected == 0 ? ETIMEDOUT : errno;
    }
    return 0;
}

static jstring qcl041_new_string(JNIEnv* env, const char* text) {
    return (*env)->NewStringUTF(env, text == NULL ? "" : text);
}

JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyquest_qcl041_Qcl041NativeSocketProbe_nativeLibraryInfo(
        JNIEnv* env,
        jclass clazz) {
    (void) clazz;
    return qcl041_new_string(env, "qcl041_socket_probe/native_fd_android_setsocknetwork/v1");
}

JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyquest_qcl041_Qcl041NativeSocketProbe_nativeSendUdp(
        JNIEnv* env,
        jclass clazz,
        jlong network_handle,
        jstring target_address_j,
        jint target_port,
        jstring payload_prefix_j,
        jint sends) {
    (void) clazz;
    char target_address[128];
    char target_address_json[256];
    char payload_prefix[512];
    qcl041_copy_jstring(env, target_address_j, target_address, sizeof(target_address));
    qcl041_copy_jstring(env, payload_prefix_j, payload_prefix, sizeof(payload_prefix));
    qcl041_json_escape(target_address, target_address_json, sizeof(target_address_json));

    char json[4096];
    struct sockaddr_in target_addr;
    if (!qcl041_parse_ipv4_target(target_address, target_port, &target_addr)) {
        snprintf(
                json,
                sizeof(json),
                "{\"schema\":\"rusty.quest.qcl041_native_socket_probe.v1\","
                "\"mode\":\"udp_native_fd_network_bound\","
                "\"status\":\"blocked\","
                "\"issue\":\"invalid_ipv4_target\","
                "\"target_address\":\"%s\","
                "\"target_port\":%d}",
                target_address_json,
                target_port);
        return qcl041_new_string(env, json);
    }

    int fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (fd < 0) {
        int socket_errno = errno;
        char socket_error[256];
        qcl041_json_escape(strerror(socket_errno), socket_error, sizeof(socket_error));
        snprintf(
                json,
                sizeof(json),
                "{\"schema\":\"rusty.quest.qcl041_native_socket_probe.v1\","
                "\"mode\":\"udp_native_fd_network_bound\","
                "\"status\":\"fail\","
                "\"network_handle\":%llu,"
                "\"socket_errno\":%d,"
                "\"socket_error\":\"%s\"}",
                (unsigned long long) network_handle,
                socket_errno,
                socket_error);
        return qcl041_new_string(env, json);
    }

    int requested = sends <= 0 ? 1 : sends;
    int setsock_result = android_setsocknetwork((net_handle_t) network_handle, fd);
    int setsock_errno = setsock_result == 0 ? 0 : errno;
    int packets_sent = 0;
    long long bytes_sent = 0;
    int last_send_errno = 0;
    if (setsock_result == 0) {
        for (int sequence = 0; sequence < requested; sequence++) {
            char payload[1024];
            int payload_len = snprintf(payload, sizeof(payload), "%s;seq=%d", payload_prefix, sequence);
            if (payload_len < 0) {
                last_send_errno = EINVAL;
                break;
            }
            if (payload_len >= (int) sizeof(payload)) {
                payload_len = (int) sizeof(payload) - 1;
            }
            ssize_t sent = sendto(
                    fd,
                    payload,
                    (size_t) payload_len,
                    0,
                    (struct sockaddr*) &target_addr,
                    sizeof(target_addr));
            if (sent < 0) {
                last_send_errno = errno;
                break;
            }
            packets_sent++;
            bytes_sent += sent;
        }
    }

    char local_socket[128];
    qcl041_socket_name(fd, local_socket, sizeof(local_socket));
    close(fd);

    char setsock_error[256];
    char send_error[256];
    char local_socket_json[256];
    qcl041_json_escape(setsock_errno == 0 ? "" : strerror(setsock_errno), setsock_error, sizeof(setsock_error));
    qcl041_json_escape(last_send_errno == 0 ? "" : strerror(last_send_errno), send_error, sizeof(send_error));
    qcl041_json_escape(local_socket, local_socket_json, sizeof(local_socket_json));
    const char* status = setsock_result == 0 && packets_sent == requested ? "pass" : "fail";
    snprintf(
            json,
            sizeof(json),
            "{\"schema\":\"rusty.quest.qcl041_native_socket_probe.v1\","
            "\"mode\":\"udp_native_fd_network_bound\","
            "\"status\":\"%s\","
            "\"network_handle\":%llu,"
            "\"setsocknetwork_result\":%d,"
            "\"setsocknetwork_errno\":%d,"
            "\"setsocknetwork_error\":\"%s\","
            "\"target_address\":\"%s\","
            "\"target_port\":%d,"
            "\"packets_requested\":%d,"
            "\"packets_sent\":%d,"
            "\"bytes_sent\":%lld,"
            "\"last_send_errno\":%d,"
            "\"last_send_error\":\"%s\","
            "\"local_socket\":\"%s\"}",
            status,
            (unsigned long long) network_handle,
            setsock_result,
            setsock_errno,
            setsock_error,
            target_address_json,
            target_port,
            requested,
            packets_sent,
            bytes_sent,
            last_send_errno,
            send_error,
            local_socket_json);
    return qcl041_new_string(env, json);
}

JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyquest_qcl041_Qcl041NativeSocketProbe_nativeConnectTcp(
        JNIEnv* env,
        jclass clazz,
        jlong network_handle,
        jstring target_address_j,
        jint target_port,
        jstring payload_j,
        jint timeout_ms) {
    (void) clazz;
    char target_address[128];
    char target_address_json[256];
    char payload[1024];
    qcl041_copy_jstring(env, target_address_j, target_address, sizeof(target_address));
    qcl041_copy_jstring(env, payload_j, payload, sizeof(payload));
    qcl041_json_escape(target_address, target_address_json, sizeof(target_address_json));

    char json[4096];
    struct sockaddr_in target_addr;
    if (!qcl041_parse_ipv4_target(target_address, target_port, &target_addr)) {
        snprintf(
                json,
                sizeof(json),
                "{\"schema\":\"rusty.quest.qcl041_native_socket_probe.v1\","
                "\"mode\":\"tcp_native_fd_network_bound\","
                "\"status\":\"blocked\","
                "\"issue\":\"invalid_ipv4_target\","
                "\"target_address\":\"%s\","
                "\"target_port\":%d}",
                target_address_json,
                target_port);
        return qcl041_new_string(env, json);
    }

    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (fd < 0) {
        int socket_errno = errno;
        char socket_error[256];
        qcl041_json_escape(strerror(socket_errno), socket_error, sizeof(socket_error));
        snprintf(
                json,
                sizeof(json),
                "{\"schema\":\"rusty.quest.qcl041_native_socket_probe.v1\","
                "\"mode\":\"tcp_native_fd_network_bound\","
                "\"status\":\"fail\","
                "\"network_handle\":%llu,"
                "\"socket_errno\":%d,"
                "\"socket_error\":\"%s\"}",
                (unsigned long long) network_handle,
                socket_errno,
                socket_error);
        return qcl041_new_string(env, json);
    }

    long long started_ms = qcl041_now_ms();
    int effective_timeout_ms = timeout_ms < 1000 ? 1000 : timeout_ms;
    int setsock_result = android_setsocknetwork((net_handle_t) network_handle, fd);
    int setsock_errno = setsock_result == 0 ? 0 : errno;
    int connect_errno = 0;
    int connected = 0;
    ssize_t bytes_written = 0;
    int write_errno = 0;
    int read_errno = 0;
    char reply[1024] = {0};

    if (setsock_result == 0) {
        int flags = fcntl(fd, F_GETFL, 0);
        if (flags >= 0) {
            fcntl(fd, F_SETFL, flags | O_NONBLOCK);
        }
        int connect_result = connect(fd, (struct sockaddr*) &target_addr, sizeof(target_addr));
        if (connect_result == 0) {
            connected = 1;
        } else if (errno == EINPROGRESS) {
            connect_errno = qcl041_wait_for_connect(fd, effective_timeout_ms);
            connected = connect_errno == 0;
        } else {
            connect_errno = errno;
        }
        if (flags >= 0) {
            fcntl(fd, F_SETFL, flags);
        }

        if (connected) {
            char line[1200];
            int line_len = snprintf(line, sizeof(line), "%s\n", payload);
            if (line_len < 0) {
                write_errno = EINVAL;
            } else {
                if (line_len >= (int) sizeof(line)) {
                    line_len = (int) sizeof(line) - 1;
                }
                bytes_written = send(fd, line, (size_t) line_len, 0);
                if (bytes_written < 0) {
                    write_errno = errno;
                    bytes_written = 0;
                }
            }
            if (bytes_written > 0) {
                read_errno = qcl041_wait_for_read(fd, effective_timeout_ms);
                if (read_errno == 0) {
                    ssize_t bytes_read = recv(fd, reply, sizeof(reply) - 1, 0);
                    if (bytes_read < 0) {
                        read_errno = errno;
                    } else {
                        reply[bytes_read] = '\0';
                        for (ssize_t index = bytes_read - 1; index >= 0; index--) {
                            if (reply[index] == '\n' || reply[index] == '\r') {
                                reply[index] = '\0';
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    char local_socket[128];
    qcl041_socket_name(fd, local_socket, sizeof(local_socket));
    close(fd);

    long long elapsed_ms = qcl041_now_ms() - started_ms;
    char setsock_error[256];
    char connect_error[256];
    char write_error[256];
    char read_error[256];
    char reply_json[2048];
    char local_socket_json[256];
    qcl041_json_escape(setsock_errno == 0 ? "" : strerror(setsock_errno), setsock_error, sizeof(setsock_error));
    qcl041_json_escape(connect_errno == 0 ? "" : strerror(connect_errno), connect_error, sizeof(connect_error));
    qcl041_json_escape(write_errno == 0 ? "" : strerror(write_errno), write_error, sizeof(write_error));
    qcl041_json_escape(read_errno == 0 ? "" : strerror(read_errno), read_error, sizeof(read_error));
    qcl041_json_escape(reply, reply_json, sizeof(reply_json));
    qcl041_json_escape(local_socket, local_socket_json, sizeof(local_socket_json));
    const char* status = connected && bytes_written > 0 ? "pass" : "fail";
    snprintf(
            json,
            sizeof(json),
            "{\"schema\":\"rusty.quest.qcl041_native_socket_probe.v1\","
            "\"mode\":\"tcp_native_fd_network_bound\","
            "\"status\":\"%s\","
            "\"network_handle\":%llu,"
            "\"setsocknetwork_result\":%d,"
            "\"setsocknetwork_errno\":%d,"
            "\"setsocknetwork_error\":\"%s\","
            "\"target_address\":\"%s\","
            "\"target_port\":%d,"
            "\"connected\":%s,"
            "\"connect_errno\":%d,"
            "\"connect_error\":\"%s\","
            "\"connect_ms\":%lld,"
            "\"bytes_written\":%lld,"
            "\"write_errno\":%d,"
            "\"write_error\":\"%s\","
            "\"read_errno\":%d,"
            "\"read_error\":\"%s\","
            "\"reply\":\"%s\","
            "\"local_socket\":\"%s\"}",
            status,
            (unsigned long long) network_handle,
            setsock_result,
            setsock_errno,
            setsock_error,
            target_address_json,
            target_port,
            connected ? "true" : "false",
            connect_errno,
            connect_error,
            elapsed_ms,
            (long long) bytes_written,
            write_errno,
            write_error,
            read_errno,
            read_error,
            reply_json,
            local_socket_json);
    return qcl041_new_string(env, json);
}
