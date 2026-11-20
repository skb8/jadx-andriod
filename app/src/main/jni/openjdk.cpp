#include <jni.h>
#include <dlfcn.h>
#include <malloc.h>

#include <string>
#include <vector>

using CreateJvm = jint (*)(JavaVM**, void**, void*);

static std::string from_java(JNIEnv* env, jstring value) {
    const char* text = env->GetStringUTFChars(value, nullptr);
    if (text == nullptr) {
        return {};
    }
    std::string result(text);
    env->ReleaseStringUTFChars(value, text);
    return result;
}

static std::string execute(const std::string& home, const std::string& jar,
                           const std::string& work, const std::vector<std::string>& args) {
#if defined(__aarch64__)
    mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE);
#endif
    void* library = dlopen((home + "/lib/server/libjvm.so").c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (library == nullptr) {
        return dlerror();
    }
    auto create = reinterpret_cast<CreateJvm>(dlsym(library, "JNI_CreateJavaVM"));
    if (create == nullptr) {
        return "JNI_CreateJavaVM unavailable";
    }

    std::vector<std::string> values{
        "-Djava.home=" + home,
        "-Djava.class.path=" + jar,
        "-Djava.io.tmpdir=" + work,
        "-Duser.home=" + work,
        "-Duser.dir=" + work,
        "-XX:ErrorFile=" + work + "/hs_err_pid%p.log",
        "-Djava.awt.headless=true",
        "-XX:-UsePerfData",
        "-XX:+UseSerialGC",
        "-Xms32m",
        "-Xmx768m",
        "-Xint",
        "-Xrs",
    };
    std::vector<JavaVMOption> options;
    for (auto& value : values) {
        options.push_back({value.data(), nullptr});
    }
    JavaVMInitArgs vm_args{
        JNI_VERSION_1_8, static_cast<jint>(options.size()), options.data(), JNI_FALSE
    };
    JavaVM* vm = nullptr;
    JNIEnv* openjdk = nullptr;
    jint result = create(&vm, reinterpret_cast<void**>(&openjdk), &vm_args);
    if (result != JNI_OK) {
        return "JNI_CreateJavaVM: " + std::to_string(result);
    }

    jclass string_type = openjdk->FindClass("java/lang/String");
    jclass cli = openjdk->FindClass("jadx/cli/JadxCLI");
    if (string_type == nullptr || cli == nullptr) {
        openjdk->ExceptionDescribe();
        return "OpenJDK could not load JadxCLI";
    }
    jmethodID entry = openjdk->GetStaticMethodID(cli, "execute", "([Ljava/lang/String;)I");
    if (entry == nullptr) {
        openjdk->ExceptionDescribe();
        return "JadxCLI.execute is unavailable";
    }
    if (args.empty()) {
        jclass system = openjdk->FindClass("java/lang/System");
        if (system == nullptr) {
            openjdk->ExceptionDescribe();
            return "OpenJDK could not load System";
        }
        jmethodID property = openjdk->GetStaticMethodID(system, "getProperty",
                "(Ljava/lang/String;)Ljava/lang/String;");
        if (property == nullptr) {
            openjdk->ExceptionDescribe();
            return "OpenJDK could not read its installed version";
        }
        auto key = openjdk->NewStringUTF("java.runtime.version");
        auto version = static_cast<jstring>(openjdk->CallStaticObjectMethod(system, property, key));
        if (version == nullptr || openjdk->ExceptionCheck()) {
            openjdk->ExceptionDescribe();
            return "OpenJDK could not report its installed version";
        }
        return "OK " + from_java(openjdk, version);
    }
    auto java_args = openjdk->NewObjectArray(static_cast<jsize>(args.size()), string_type, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(args.size()); ++i) {
        jstring value = openjdk->NewStringUTF(args[i].c_str());
        openjdk->SetObjectArrayElement(java_args, i, value);
        openjdk->DeleteLocalRef(value);
    }
    if (openjdk->ExceptionCheck()) {
        openjdk->ExceptionDescribe();
        return "OpenJDK could not allocate the arguments";
    }
    result = openjdk->CallStaticIntMethod(cli, entry, java_args);
    if (openjdk->ExceptionCheck()) {
        openjdk->ExceptionDescribe();
        return "JADX raised an exception in OpenJDK";
    }
    if (result == 0 || result == 3) {
        // The CLI uses 3 for a completed export with decompilation errors.
        return "OK " + std::to_string(result);
    }
    return "JADX CLI exit code: " + std::to_string(result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_skb8_jadxandroid_OpenJdkService_execute(JNIEnv* env, jclass,
        jstring home, jstring jar, jstring work, jobjectArray input) {
    std::vector<std::string> args;
    for (jsize i = 0; i < env->GetArrayLength(input); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(input, i));
        args.push_back(from_java(env, value));
        env->DeleteLocalRef(value);
    }
    std::string result = execute(from_java(env, home), from_java(env, jar), from_java(env, work), args);
    return env->NewStringUTF(result.c_str());
}
