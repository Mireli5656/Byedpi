/*
 * ByeDPI JNI bridge. MIT License (see repository LICENSE).
 *
 * Runs the unmodified upstream ByeDPI proxy (https://github.com/hufrea/byedpi, MIT)
 * inside a forked child process:
 *
 *   nativeStart(args)  fork() -> child calls ciadpi_main(argc, argv); the calling Java
 *                      thread blocks in waitpid() until the child exits.
 *   nativeStop()       SIGTERM, then SIGKILL if the child does not exit in time.
 *
 * A fresh child per start means upstream global state never has to be reset, and a
 * crash in the engine cannot take the app down. The child dies with its parent thread
 * (PR_SET_PDEATHSIG), so it cannot be left holding the listening port.
 * Nothing about traffic (hosts, addresses, payloads) is ever logged here.
 */

#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

/* Upstream main() from byedpi/main.c, renamed by -Dmain=ciadpi_main. */
extern int ciadpi_main(int argc, char **argv);

#define LOG_TAG "ByeDPI-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define PROGRAM_NAME "ciadpi"

/* Return codes of nativeStart() that are not engine exit codes. */
#define ERR_ALREADY_RUNNING (-2)
#define ERR_OUT_OF_MEMORY   (-3)
#define ERR_FORK_FAILED     (-4)
#define ERR_WAIT_FAILED     (-5)

static atomic_int g_running = 0;         /* 1 while nativeStart() is active */
static atomic_int g_child_pid = 0;       /* pid of the engine process, 0 if none */
static atomic_int g_stop_requested = 0;  /* set by nativeStop(), cleared when start returns */

static void free_argv(char **argv, int argc)
{
    if (argv == NULL) {
        return;
    }
    for (int i = 0; i < argc; i++) {
        free(argv[i]);
    }
    free(argv);
}

static char **build_argv(JNIEnv *env, jobjectArray jargs, int *out_argc)
{
    jsize extra = (jargs != NULL) ? (*env)->GetArrayLength(env, jargs) : 0;
    char **argv = (char **)calloc((size_t)extra + 2, sizeof(char *));
    if (argv == NULL) {
        return NULL;
    }

    int argc = 0;
    argv[argc] = strdup(PROGRAM_NAME);
    if (argv[argc] == NULL) {
        free(argv);
        return NULL;
    }
    argc++;

    for (jsize i = 0; i < extra; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, jargs, i);
        if (js == NULL) {
            continue;
        }
        const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
        char *copy = (utf != NULL) ? strdup(utf) : NULL;
        if (utf != NULL) {
            (*env)->ReleaseStringUTFChars(env, js, utf);
        }
        (*env)->DeleteLocalRef(env, js);
        if (copy == NULL) {
            free_argv(argv, argc);
            return NULL;
        }
        argv[argc++] = copy;
    }

    argv[argc] = NULL;
    *out_argc = argc;
    return argv;
}

/* Runs in the forked child. Never returns. */
static void run_child(int argc, char **argv)
{
    pid_t parent = getppid();

    /* Die together with the thread that forked us (prevents an orphan holding the port). */
    prctl(PR_SET_PDEATHSIG, SIGKILL);
    if (getppid() != parent) {
        _exit(1);
    }

    sigset_t empty;
    sigemptyset(&empty);
    sigprocmask(SIG_SETMASK, &empty, NULL);
    signal(SIGTERM, SIG_DFL);
    signal(SIGINT, SIG_DFL);
    signal(SIGPIPE, SIG_IGN);

    /* The engine's console output is not needed and must never leak traffic details. */
    int null_fd = open("/dev/null", O_RDWR);
    if (null_fd >= 0) {
        dup2(null_fd, 0);
        dup2(null_fd, 1);
        dup2(null_fd, 2);
        if (null_fd > 2) {
            close(null_fd);
        }
    }

    int rc = ciadpi_main(argc, argv);
    _exit(rc < 0 ? 1 : (rc & 0xFF));
}

JNIEXPORT jint JNICALL
Java_com_byedpi_app_core_ByeDpiNative_nativeStart(JNIEnv *env, jobject thiz, jobjectArray jargs)
{
    (void)thiz;

    int expected = 0;
    if (!atomic_compare_exchange_strong(&g_running, &expected, 1)) {
        LOGW("start ignored: engine is already running");
        return ERR_ALREADY_RUNNING;
    }

    int argc = 0;
    char **argv = build_argv(env, jargs, &argc);
    if (argv == NULL) {
        atomic_store(&g_running, 0);
        return ERR_OUT_OF_MEMORY;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed (errno %d)", errno);
        free_argv(argv, argc);
        atomic_store(&g_running, 0);
        return ERR_FORK_FAILED;
    }
    if (pid == 0) {
        run_child(argc, argv); /* never returns */
        _exit(127);
    }

    atomic_store(&g_child_pid, (int)pid);
    LOGI("engine process started (pid %d)", (int)pid);

    /* A stop may have been requested before the pid was published. */
    if (atomic_load(&g_stop_requested)) {
        kill(pid, SIGTERM);
    }

    int status = 0;
    pid_t waited;
    do {
        waited = waitpid(pid, &status, 0);
    } while (waited < 0 && errno == EINTR);

    atomic_store(&g_child_pid, 0);
    int stopped_by_request = atomic_exchange(&g_stop_requested, 0);
    free_argv(argv, argc);

    jint result;
    if (waited < 0) {
        LOGE("waitpid failed (errno %d)", errno);
        result = ERR_WAIT_FAILED;
    } else if (WIFEXITED(status)) {
        result = WEXITSTATUS(status);
        LOGI("engine process exited with code %d", (int)result);
    } else if (WIFSIGNALED(status)) {
        int sig = WTERMSIG(status);
        LOGI("engine process killed by signal %d", sig);
        result = (stopped_by_request && (sig == SIGTERM || sig == SIGKILL)) ? 0 : 128 + sig;
    } else {
        result = ERR_WAIT_FAILED;
    }

    atomic_store(&g_running, 0);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_byedpi_app_core_ByeDpiNative_nativeStop(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;

    if (!atomic_load(&g_running)) {
        return JNI_TRUE;
    }

    atomic_store(&g_stop_requested, 1);
    pid_t pid = (pid_t)atomic_load(&g_child_pid);
    if (pid > 0) {
        kill(pid, SIGTERM);
    }

    /* nativeStart() reaps the child and clears g_running. Give it 1.5 s. */
    for (int i = 0; i < 15; i++) {
        if (!atomic_load(&g_running)) {
            return JNI_TRUE;
        }
        usleep(100 * 1000);
    }

    pid = (pid_t)atomic_load(&g_child_pid);
    if (pid > 0) {
        LOGW("engine did not stop on SIGTERM, sending SIGKILL");
        kill(pid, SIGKILL);
    }
    for (int i = 0; i < 20; i++) {
        if (!atomic_load(&g_running)) {
            return JNI_TRUE;
        }
        usleep(100 * 1000);
    }
    return JNI_FALSE;
}
