#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <errno.h>
#include <sys/stat.h>

/*
 * MineHost Native JVM Launcher
 *
 * Executes Java runtime using OpenJDK libjli / JNI or execv.
 */

typedef int (*JLI_Launch_fn)(
    int argc, char** argv,
    int jargc, const char** jargv,
    int appclassc, const char** appclassv,
    const char* fullversion,
    const char* dotversion,
    const char* pname,
    const char* lname,
    int javaargs,
    int cpwildcard,
    int javaw,
    int ergo
);

int main(int argc, char** argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    const char* runtime_home = getenv("MINEHOST_RUNTIME_HOME");
    const char* java_major = getenv("MINEHOST_JAVA_MAJOR");
    const char* arg_count_str = getenv("MINEHOST_ARG_COUNT");
    const char* libjli_path_env = getenv("MINEHOST_LIBJLI_PATH");

    char libjli_path[1024] = {0};
    char java_bin_path[1024] = {0};

    int num_java_args = 0;
    char** java_args = NULL;

    if (arg_count_str != NULL) {
        num_java_args = atoi(arg_count_str);
    }

    if (num_java_args > 0) {
        java_args = (char**) malloc(sizeof(char*) * num_java_args);
        for (int i = 0; i < num_java_args; i++) {
            char env_name[64];
            snprintf(env_name, sizeof(env_name), "MINEHOST_ARG_%d", i);
            const char* val = getenv(env_name);
            java_args[i] = (char*) (val ? val : "");
        }
    } else if (argc > 1) {
        int start_idx = 1;
        if (strstr(argv[1], "java") != NULL && access(argv[1], F_OK) == 0) {
            snprintf(java_bin_path, sizeof(java_bin_path), "%s", argv[1]);
            start_idx = 2;
        }
        num_java_args = argc - start_idx;
        if (num_java_args > 0) {
            java_args = (char**) malloc(sizeof(char*) * num_java_args);
            for (int i = 0; i < num_java_args; i++) {
                java_args[i] = argv[start_idx + i];
            }
        }
    }

    if (!runtime_home || strlen(runtime_home) == 0) {
        runtime_home = getenv("JAVA_HOME");
    }

    if (libjli_path_env && strlen(libjli_path_env) > 0) {
        snprintf(libjli_path, sizeof(libjli_path), "%s", libjli_path_env);
    } else if (runtime_home && strlen(runtime_home) > 0) {
        snprintf(libjli_path, sizeof(libjli_path), "%s/lib/libjli.so", runtime_home);
        if (access(libjli_path, F_OK) != 0) {
            snprintf(libjli_path, sizeof(libjli_path), "%s/lib/jli/libjli.so", runtime_home);
        }
    }

    if (java_bin_path[0] == '\0' && runtime_home && strlen(runtime_home) > 0) {
        snprintf(java_bin_path, sizeof(java_bin_path), "%s/bin/java", runtime_home);
    }

    void* handle = NULL;
    if (libjli_path[0] != '\0' && access(libjli_path, F_OK) == 0) {
        handle = dlopen(libjli_path, RTLD_NOW | RTLD_GLOBAL);
    }

    if (handle != NULL) {
        JLI_Launch_fn jli_launch = (JLI_Launch_fn) dlsym(handle, "JLI_Launch");
        if (jli_launch != NULL) {
            int jli_argc = num_java_args + 1;
            char** jli_argv = (char**) malloc(sizeof(char*) * (jli_argc + 1));
            jli_argv[0] = (char*) "java";
            for (int i = 0; i < num_java_args; i++) {
                jli_argv[i + 1] = java_args[i];
            }
            jli_argv[jli_argc] = NULL;

            const char* ver = java_major ? java_major : "17";

            int res = jli_launch(
                jli_argc, jli_argv,
                0, NULL,
                0, NULL,
                ver,
                ver,
                "java",
                "java",
                1,
                1,
                0,
                0
            );
            return res;
        }
    }

    if (java_bin_path[0] != '\0' && access(java_bin_path, F_OK) == 0) {
        char** exec_argv = (char**) malloc(sizeof(char*) * (num_java_args + 2));
        exec_argv[0] = java_bin_path;
        for (int i = 0; i < num_java_args; i++) {
            exec_argv[i + 1] = java_args[i];
        }
        exec_argv[num_java_args + 1] = NULL;

        execv(java_bin_path, exec_argv);
        fprintf(stderr, "execv failed for %s: %s\n", java_bin_path, strerror(errno));
    } else {
        fprintf(stderr, "MineHost launcher error: Neither libjli.so (%s) nor java binary (%s) could be loaded/found.\n",
                libjli_path, java_bin_path);
    }

    return 1;
}
