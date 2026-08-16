/*
 * A pseudo-terminal for the container backend.
 *
 * A shell driven through plain pipes is not a shell: isatty() returns false, so there
 * is no line editing, no job control, no colour, and no way for anything full-screen
 * to work. Bionic has openpty/forkpty, so the child gets a real terminal and the app
 * reads and writes the master side.
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pty.h>
#include <stdio.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void throw_io(JNIEnv *env, const char *message) {
    jclass clazz = (*env)->FindClass(env, "java/io/IOException");
    if (clazz != NULL) (*env)->ThrowNew(env, clazz, message);
}

/** Copies a Java string array into a NULL-terminated char* array. */
static char **to_array(JNIEnv *env, jobjectArray source) {
    jsize count = (*env)->GetArrayLength(env, source);
    char **result = calloc((size_t) count + 1, sizeof(char *));
    if (result == NULL) return NULL;

    for (jsize i = 0; i < count; i++) {
        jstring element = (jstring) (*env)->GetObjectArrayElement(env, source, i);
        const char *chars = (*env)->GetStringUTFChars(env, element, NULL);
        result[i] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, element, chars);
        (*env)->DeleteLocalRef(env, element);
    }
    result[count] = NULL;
    return result;
}

static void free_array(char **array) {
    if (array == NULL) return;
    for (char **entry = array; *entry != NULL; entry++) free(*entry);
    free(array);
}

/**
 * Forks a child attached to a new pseudo-terminal.
 *
 * Returns the master file descriptor; the process id is written into pidOut[0]. The
 * child never returns from here: it either execs or exits.
 */
JNIEXPORT jint JNICALL
Java_com_mazevm_android_container_Pty_forkExec(
        JNIEnv *env, jclass clazz,
        jstring jExecutable, jobjectArray jArgv, jobjectArray jEnvp,
        jstring jWorkingDir, jint columns, jint rows, jintArray pidOut) {
    (void) clazz;

    const char *executable = (*env)->GetStringUTFChars(env, jExecutable, NULL);
    const char *workingDir = jWorkingDir == NULL
                             ? NULL
                             : (*env)->GetStringUTFChars(env, jWorkingDir, NULL);
    char **argv = to_array(env, jArgv);
    char **envp = to_array(env, jEnvp);

    if (argv == NULL || envp == NULL) {
        throw_io(env, "out of memory building the argument list");
        goto fail;
    }

    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_col = (unsigned short) (columns > 0 ? columns : 80);
    size.ws_row = (unsigned short) (rows > 0 ? rows : 24);

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &size);

    if (pid < 0) {
        throw_io(env, strerror(errno));
        goto fail;
    }

    if (pid == 0) {
        /*
         * Child. Leaving the parent's signal dispositions in place would give the
         * shell a SIGINT handler it never installed, so they are reset first.
         */
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);
        for (int signal_number = 1; signal_number < NSIG; signal_number++) {
            signal(signal_number, SIG_DFL);
        }

        if (workingDir != NULL && chdir(workingDir) != 0) {
            /* Not fatal: the image's WorkingDir may simply not exist yet. */
        }

        execve(executable, argv, envp);
        /* Only reached when exec fails; the message lands on the terminal. */
        fprintf(stderr, "\r\nMazeVM: cannot execute %s: %s\r\n",
                executable, strerror(errno));
        _exit(127);
    }

    /* Parent. */
    jint pidValue = (jint) pid;
    (*env)->SetIntArrayRegion(env, pidOut, 0, 1, &pidValue);

    free_array(argv);
    free_array(envp);
    (*env)->ReleaseStringUTFChars(env, jExecutable, executable);
    if (workingDir != NULL) (*env)->ReleaseStringUTFChars(env, jWorkingDir, workingDir);
    return master;

    fail:
    free_array(argv);
    free_array(envp);
    (*env)->ReleaseStringUTFChars(env, jExecutable, executable);
    if (workingDir != NULL) (*env)->ReleaseStringUTFChars(env, jWorkingDir, workingDir);
    return -1;
}

/** Tells the guest its window changed, which is what makes full-screen programs reflow. */
JNIEXPORT void JNICALL
Java_com_mazevm_android_container_Pty_resize(
        JNIEnv *env, jclass clazz, jint fd, jint columns, jint rows) {
    (void) env;
    (void) clazz;
    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_col = (unsigned short) (columns > 0 ? columns : 80);
    size.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ioctl(fd, TIOCSWINSZ, &size);
}

/** Blocks until the child exits, returning its status. */
JNIEXPORT jint JNICALL
Java_com_mazevm_android_container_Pty_waitFor(
        JNIEnv *env, jclass clazz, jint pid) {
    (void) env;
    (void) clazz;
    int status = 0;
    while (waitpid((pid_t) pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_mazevm_android_container_Pty_terminate(
        JNIEnv *env, jclass clazz, jint pid, jboolean force) {
    (void) env;
    (void) clazz;
    /* Negated so the whole process group goes, not just the shell. */
    kill(-(pid_t) pid, force ? SIGKILL : SIGHUP);
    kill((pid_t) pid, force ? SIGKILL : SIGHUP);
}

JNIEXPORT void JNICALL
Java_com_mazevm_android_container_Pty_closeFd(
        JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    if (fd >= 0) close(fd);
}

/*
 * Duplicates the terminal descriptor.
 *
 * The read and write sides are handed to separate Java streams, and every stream that
 * wraps a descriptor expects to own it. Sharing one descriptor between them means it
 * gets closed more than once, and since Android 10 fdsan aborts the whole process the
 * moment a descriptor is closed by something other than its recorded owner. Giving
 * each side its own descriptor keeps ownership one-to-one.
 */
JNIEXPORT jint JNICALL
Java_com_mazevm_android_container_Pty_dupFd(
        JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    return fd < 0 ? -1 : dup(fd);
}
