// SPDX-License-Identifier: GPL-3.0-or-later
//
// This bridge creates a PTY for the separately built GPL Mosh executable. It
// deliberately does not invoke a shell: profile fields become execve argv
// items, and the one-shot MOSH_KEY is supplied through an environment entry.

#include <jni.h>

#include <android/log.h>
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

extern char** environ;

namespace {

constexpr char kLogTag[] = "MangoSSH";

void logInfo(const char* event) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", event);
}

void logWarn(const char* event) {
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", event);
}

void throwIOException(JNIEnv* env, const char* operation) {
    const int error = errno;
    jclass exceptionClass = env->FindClass("java/io/IOException");
    if (exceptionClass == nullptr) {
        return;
    }
    std::string message(operation);
    message.append(" failed: ");
    message.append(std::strerror(error));
    env->ThrowNew(exceptionClass, message.c_str());
}

void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

bool closeFd(int fd) {
    if (fd < 0) {
        return true;
    }
    while (close(fd) == -1) {
        if (errno != EINTR) {
            return false;
        }
    }
    return true;
}

bool writeAll(int fd, const void* value, size_t size) {
    const auto* bytes = static_cast<const unsigned char*>(value);
    size_t written = 0;
    while (written < size) {
        const ssize_t count = write(fd, bytes + written, size - written);
        if (count < 0 && errno == EINTR) {
            continue;
        }
        if (count <= 0) {
            return false;
        }
        written += static_cast<size_t>(count);
    }
    return true;
}

bool readFully(int fd, void* value, size_t size, bool* reachedEnd) {
    auto* bytes = static_cast<unsigned char*>(value);
    size_t readCount = 0;
    while (readCount < size) {
        const ssize_t count = read(fd, bytes + readCount, size - readCount);
        if (count == 0) {
            *reachedEnd = true;
            return readCount == 0;
        }
        if (count < 0 && errno == EINTR) {
            continue;
        }
        if (count < 0) {
            return false;
        }
        readCount += static_cast<size_t>(count);
    }
    *reachedEnd = false;
    return true;
}

std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        throwIllegalArgument(env, "Native Mosh argument is missing");
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool configurePtySize(int fd, int columns, int rows) {
    winsize size{};
    size.ws_col = static_cast<unsigned short>(columns);
    size.ws_row = static_cast<unsigned short>(rows);
    if (ioctl(fd, TIOCSWINSZ, &size) == -1) {
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_website_sung_mangossh_session_MoshPtyNative_start(
    JNIEnv* env,
    jobject /* thiz */,
    jstring executableValue,
    jstring hostValue,
    jint port,
    jstring keyValue,
    jstring terminalValue,
    jstring terminfoDirectoryValue,
    jint columns,
    jint rows) {
    if (port < 1 || port > 65535 || columns < 1 || rows < 1) {
        throwIllegalArgument(env, "Native Mosh arguments are invalid");
        return nullptr;
    }

    std::string executable = jstringToUtf8(env, executableValue);
    std::string host = jstringToUtf8(env, hostValue);
    std::string key = jstringToUtf8(env, keyValue);
    std::string terminal = jstringToUtf8(env, terminalValue);
    std::string terminfoDirectory = jstringToUtf8(env, terminfoDirectoryValue);
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    const auto wipeKey = [&key]() { std::fill(key.begin(), key.end(), '\0'); };

    int masterFd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (masterFd == -1) {
        wipeKey();
        throwIOException(env, "posix_openpt");
        return nullptr;
    }
    if (grantpt(masterFd) == -1 || unlockpt(masterFd) == -1) {
        const int error = errno;
        closeFd(masterFd);
        wipeKey();
        errno = error;
        throwIOException(env, "grantpt/unlockpt");
        return nullptr;
    }

    char slavePath[PATH_MAX]{};
    const int ptsnameResult = ptsname_r(masterFd, slavePath, sizeof(slavePath));
    if (ptsnameResult != 0) {
        closeFd(masterFd);
        wipeKey();
        errno = ptsnameResult;
        throwIOException(env, "ptsname_r");
        return nullptr;
    }

    int errorPipe[2]{};
    if (pipe2(errorPipe, O_CLOEXEC) == -1) {
        const int error = errno;
        closeFd(masterFd);
        wipeKey();
        errno = error;
        throwIOException(env, "pipe2");
        return nullptr;
    }

    const pid_t childPid = fork();
    if (childPid == -1) {
        const int error = errno;
        closeFd(errorPipe[0]);
        closeFd(errorPipe[1]);
        closeFd(masterFd);
        wipeKey();
        errno = error;
        throwIOException(env, "fork");
        return nullptr;
    }

    if (childPid == 0) {
        closeFd(errorPipe[0]);
        closeFd(masterFd);
        if (setsid() == -1) {
            const int error = errno;
            writeAll(errorPipe[1], &error, sizeof(error));
            _exit(126);
        }

        const int slaveFd = open(slavePath, O_RDWR | O_NOCTTY);
        if (slaveFd == -1 || ioctl(slaveFd, TIOCSCTTY, 0) == -1 ||
            dup2(slaveFd, STDIN_FILENO) == -1 || dup2(slaveFd, STDOUT_FILENO) == -1 ||
            dup2(slaveFd, STDERR_FILENO) == -1) {
            const int error = errno;
            if (slaveFd >= 0) {
                closeFd(slaveFd);
            }
            writeAll(errorPipe[1], &error, sizeof(error));
            _exit(126);
        }
        if (slaveFd > STDERR_FILENO) {
            closeFd(slaveFd);
        }
        if (!configurePtySize(STDIN_FILENO, columns, rows)) {
            const int error = errno;
            writeAll(errorPipe[1], &error, sizeof(error));
            _exit(126);
        }

        if (setenv("MOSH_KEY", key.c_str(), 1) != 0 ||
            setenv("TERM", terminal.c_str(), 1) != 0 ||
            setenv("TERMINFO", terminfoDirectory.c_str(), 1) != 0 ||
            setenv("LANG", "C.UTF-8", 1) != 0) {
            const int error = errno;
            writeAll(errorPipe[1], &error, sizeof(error));
            _exit(126);
        }

        const std::string portText = std::to_string(port);
        char* const arguments[] = {
            const_cast<char*>(executable.c_str()),
            const_cast<char*>(host.c_str()),
            const_cast<char*>(portText.c_str()),
            nullptr,
        };
        execve(executable.c_str(), arguments, environ);
        const int error = errno;
        writeAll(errorPipe[1], &error, sizeof(error));
        _exit(127);
    }

    closeFd(errorPipe[1]);
    int childError = 0;
    bool reachedEnd = false;
    const bool readSucceeded = readFully(errorPipe[0], &childError, sizeof(childError), &reachedEnd);
    const int readError = errno;
    closeFd(errorPipe[0]);
    if (!readSucceeded || !reachedEnd) {
        const int error = readSucceeded ? childError : readError;
        closeFd(masterFd);
        kill(childPid, SIGTERM);
        waitpid(childPid, nullptr, 0);
        wipeKey();
        errno = error;
        throwIOException(env, "execve mosh-client");
        return nullptr;
    }

    wipeKey();
    logInfo("mosh.pty.started");
    const jlong values[] = {static_cast<jlong>(masterFd), static_cast<jlong>(childPid)};
    jlongArray result = env->NewLongArray(2);
    if (result == nullptr) {
        closeFd(masterFd);
        kill(childPid, SIGTERM);
        waitpid(childPid, nullptr, 0);
        return nullptr;
    }
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_website_sung_mangossh_session_MoshPtyNative_resize(
    JNIEnv* env,
    jobject /* thiz */,
    jint masterFd,
    jint columns,
    jint rows) {
    if (masterFd < 0 || columns < 1 || rows < 1) {
        throwIllegalArgument(env, "Native Mosh resize arguments are invalid");
        return;
    }
    winsize size{};
    size.ws_col = static_cast<unsigned short>(columns);
    size.ws_row = static_cast<unsigned short>(rows);
    if (ioctl(masterFd, TIOCSWINSZ, &size) == -1) {
        throwIOException(env, "ioctl TIOCSWINSZ");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_website_sung_mangossh_session_MoshPtyNative_requestStop(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint pid) {
    if (pid > 0 && kill(static_cast<pid_t>(pid), SIGTERM) == -1 && errno != ESRCH) {
        logWarn("mosh.pty.stop_failed");
        return;
    }
    logInfo("mosh.pty.stop_requested");
}

extern "C" JNIEXPORT void JNICALL
Java_website_sung_mangossh_session_MoshPtyNative_waitForExit(
    JNIEnv* env,
    jobject /* thiz */,
    jint pid) {
    if (pid <= 0) {
        return;
    }
    int status = 0;
    while (waitpid(static_cast<pid_t>(pid), &status, 0) == -1) {
        if (errno == EINTR) {
            continue;
        }
        if (errno == ECHILD) {
            return;
        }
        throwIOException(env, "waitpid");
        return;
    }
    logInfo("mosh.pty.stopped");
}
