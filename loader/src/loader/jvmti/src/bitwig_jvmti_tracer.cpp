#include <jni.h>
#include <jvmti.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>
#include <algorithm>
#include <unordered_map>
#include <unordered_set>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#define AGENT_EXPORT extern "C" __declspec(dllexport)
#else
#include <dlfcn.h>
#define AGENT_EXPORT extern "C" __attribute__((visibility("default")))
#endif

namespace {

struct Target {
    std::string targetId;
    std::string owner;          // dotted binary name
    std::string method;
    std::string descriptor;
    std::string requestedOwner;
    std::string notes;
    jmethodID methodId = nullptr;
    jlocation entryLocation = 0;
    bool isStatic = false;
    bool installed = false;
    // Debug-only per-method budget. Clearing only the JVMTI breakpoint does
    // NOT disable any BML-patched Java method or helper functionality.
    unsigned long long entriesTraced = 0;
    bool traceBudgetExhausted = false;
};

struct ActiveTarget {
    jmethodID method = nullptr;
    std::string receiverClass;
    std::string receiverStatus;
    long long receiverTag = 0;
};

struct ThreadState {
    unsigned long long id = 0;
    std::vector<ActiveTarget> active;
};

JavaVM* g_vm = nullptr;
jvmtiEnv* g_jvmti = nullptr;
std::vector<Target> g_targets;
// Opt-in, read-only class/method inventory for version-specific mapping diagnosis.
std::vector<std::string> g_inventoryOwners;
std::vector<std::string> g_inventoryMethodNames;
std::unordered_set<std::string> g_inventoryDone;
std::mutex g_stateMutex;
std::mutex g_logMutex;
std::atomic<unsigned long long> g_seq{0};
std::atomic<unsigned long long> g_threadSeq{0};
std::atomic<bool> g_started{false};
std::atomic<bool> g_shuttingDown{false};
bool g_traceEnabled = true;
// 0 means unlimited. Bound hot methods by default so Debug Mode cannot
// continuously slow down Bitwig's UI with tens of thousands of lookups.
int g_traceEntryLimit = 1000;
FILE* g_trace = nullptr;
std::string g_tracePath;
std::string g_configPath;
std::string g_controlPath;
int g_stackDepth = 4;
bool g_receiverEnabled = true;
bool g_haveLocalVariableCapability = false;
bool g_haveTagCapability = false;
bool g_valuesEnabled = true;
bool g_tagsEnabled = true;
bool g_stringContentsEnabled = true;
int g_stringLimit = 160;
std::atomic<long long> g_nextObjectTag{0};
bool g_startupMode = false;
std::string g_classHookMode = "off";
std::string g_replacementClassPath;
std::string g_replacementOwner;
std::string g_replacementMapPath;
std::string g_helperJarPath;
std::vector<unsigned char> g_replacementClassBytes;
struct ReplacementClass {
    std::string path;
    std::vector<unsigned char> bytes;
};
std::unordered_map<std::string, ReplacementClass> g_replacementClasses;
bool g_haveAllClassHookCapability = false;
std::atomic<unsigned long long> g_classHookHits{0};

static bool classHookEnabled() { return g_classHookMode == "noop" || g_classHookMode == "copy" || g_classHookMode == "file"; }

static std::string trim(const std::string& s) {
    size_t a = 0, b = s.size();
    while (a < b && (s[a] == ' ' || s[a] == '\t' || s[a] == '\r' || s[a] == '\n')) ++a;
    while (b > a && (s[b-1] == ' ' || s[b-1] == '\t' || s[b-1] == '\r' || s[b-1] == '\n')) --b;
    return s.substr(a, b-a);
}

static std::vector<std::string> split(const std::string& s, char delim) {
    std::vector<std::string> out;
    size_t start = 0;
    for (;;) {
        size_t p = s.find(delim, start);
        out.push_back(s.substr(start, p == std::string::npos ? std::string::npos : p-start));
        if (p == std::string::npos) break;
        start = p + 1;
    }
    return out;
}

static bool readBinaryFile(const std::string& path, std::vector<unsigned char>& out);

static bool loadReplacementMap(const std::string& path, std::string& error) {
    std::ifstream f(path, std::ios::binary);
    if (!f) { error = "cannot open replacement map: " + path; return false; }
    std::string line;
    int lineNo = 0;
    while (std::getline(f, line)) {
        ++lineNo;
        if (!line.empty() && line.back() == '\r') line.pop_back();
        std::string t = trim(line);
        if (t.empty() || t[0] == '#') continue;
        auto cols = split(t, '\t');
        if (cols.size() < 2) { error = "replacement map line " + std::to_string(lineNo) + " requires owner<TAB>classfile"; return false; }
        std::string owner = trim(cols[0]);
        std::replace(owner.begin(), owner.end(), '/', '.');
        std::string classPath = trim(cols[1]);
        if (owner.empty() || classPath.empty()) { error = "replacement map line " + std::to_string(lineNo) + " has empty owner/path"; return false; }
        ReplacementClass rc;
        rc.path = classPath;
        if (!readBinaryFile(classPath, rc.bytes)) { error = "cannot read replacement class for " + owner + ": " + classPath; return false; }
        g_replacementClasses[owner] = std::move(rc);
    }
    if (g_replacementClasses.empty()) { error = "replacement map contains no classes"; return false; }
    return true;
}

static std::string tsvSafe(std::string s) {
    std::string out;
    out.reserve(s.size());
    for (unsigned char c : s) {
        if (c == '\t') out += "\\t";
        else if (c == '\r') out += "\\r";
        else if (c == '\n') out += "\\n";
        else out.push_back(static_cast<char>(c));
    }
    return out;
}

static std::string classNameFromSignature(const char* sig) {
    if (!sig) return {};
    std::string s(sig);
    if (s.size() >= 2 && s.front() == 'L' && s.back() == ';') {
        s = s.substr(1, s.size()-2);
    }
    std::replace(s.begin(), s.end(), '/', '.');
    return s;
}

static unsigned long long fnv1a64(const unsigned char* data, jint len) {
    unsigned long long h = 1469598103934665603ULL;
    if (!data || len <= 0) return h;
    for (jint i = 0; i < len; ++i) {
        h ^= static_cast<unsigned long long>(data[i]);
        h *= 1099511628211ULL;
    }
    return h;
}

static bool readBinaryFile(const std::string& path, std::vector<unsigned char>& out) {
    out.clear();
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    f.seekg(0, std::ios::end);
    std::streamoff n = f.tellg();
    if (n <= 0) return false;
    f.seekg(0, std::ios::beg);
    out.resize(static_cast<size_t>(n));
    f.read(reinterpret_cast<char*>(out.data()), n);
    return f.good() || f.gcount() == n;
}

static Target* findTargetByOwnerName(const std::string& owner) {
    for (auto& t : g_targets) if (t.owner == owner) return &t;
    return nullptr;
}

static void dealloc(unsigned char* p) {
    if (p && g_jvmti) g_jvmti->Deallocate(p);
}

static const char* errorName(jvmtiError err) {
    if (!g_jvmti) return "JVMTI_ERROR";
    char* name = nullptr;
    if (g_jvmti->GetErrorName(err, &name) == JVMTI_ERROR_NONE && name) {
        // Cannot safely return allocated memory. Use thread-local copy.
        static thread_local std::string holder;
        holder = name;
        dealloc(reinterpret_cast<unsigned char*>(name));
        return holder.c_str();
    }
    return "JVMTI_ERROR";
}

static long long epochMicros() {
    using namespace std::chrono;
    return duration_cast<microseconds>(system_clock::now().time_since_epoch()).count();
}

static long long monotonicNanos() {
    if (!g_jvmti) return 0;
    jlong t = 0;
    if (g_jvmti->GetTime(&t) != JVMTI_ERROR_NONE) return 0;
    return static_cast<long long>(t);
}

static std::string threadName(jthread thread) {
    if (!g_jvmti || !thread) return {};
    jvmtiThreadInfo info{};
    jvmtiError e = g_jvmti->GetThreadInfo(thread, &info);
    if (e != JVMTI_ERROR_NONE) return std::string("<thread-info:") + errorName(e) + ">";
    std::string name = info.name ? info.name : "";
    dealloc(reinterpret_cast<unsigned char*>(info.name));
    // thread_group and context_class_loader are JNI local refs owned by the callback frame.
    return name;
}

static std::string describeMethod(jmethodID method, jlocation location, bool includeDesc=true) {
    if (!g_jvmti || !method) return "<unknown-method>";
    char* name = nullptr;
    char* sig = nullptr;
    char* gen = nullptr;
    jclass klass = nullptr;
    std::string out;
    if (g_jvmti->GetMethodName(method, &name, &sig, &gen) != JVMTI_ERROR_NONE) {
        return "<method-name-unavailable>";
    }
    std::string owner;
    if (g_jvmti->GetMethodDeclaringClass(method, &klass) == JVMTI_ERROR_NONE && klass) {
        char* csig = nullptr;
        char* cgen = nullptr;
        if (g_jvmti->GetClassSignature(klass, &csig, &cgen) == JVMTI_ERROR_NONE) {
            owner = classNameFromSignature(csig);
        }
        dealloc(reinterpret_cast<unsigned char*>(csig));
        dealloc(reinterpret_cast<unsigned char*>(cgen));
    }
    out = owner + "." + (name ? name : "?");
    if (includeDesc && sig) out += sig;
    if (location >= 0) out += "@" + std::to_string(static_cast<long long>(location));
    dealloc(reinterpret_cast<unsigned char*>(name));
    dealloc(reinterpret_cast<unsigned char*>(sig));
    dealloc(reinterpret_cast<unsigned char*>(gen));
    return out;
}

static std::string stackContext(jthread thread) {
    if (!g_jvmti || !thread || g_stackDepth <= 0) return {};
    int cap = (std::min)(g_stackDepth, 16);
    jvmtiFrameInfo frames[16]{};
    jint count = 0;
    jvmtiError e = g_jvmti->GetStackTrace(thread, 0, cap, frames, &count);
    if (e != JVMTI_ERROR_NONE) return std::string("<stack:") + errorName(e) + ">";
    std::ostringstream ss;
    for (jint i = 0; i < count; ++i) {
        if (i) ss << " <- ";
        ss << describeMethod(frames[i].method, frames[i].location);
    }
    return ss.str();
}

static Target* findTargetByMethod(jmethodID method) {
    for (auto& t : g_targets) if (t.methodId == method) return &t;
    return nullptr;
}

static ThreadState* getThreadState(jthread thread, bool create) {
    if (!g_jvmti || !thread) return nullptr;
    void* data = nullptr;
    if (g_jvmti->GetThreadLocalStorage(thread, &data) != JVMTI_ERROR_NONE) return nullptr;
    if (!data && create) {
        ThreadState* ts = new ThreadState();
        ts->id = ++g_threadSeq;
        if (g_jvmti->SetThreadLocalStorage(thread, ts) != JVMTI_ERROR_NONE) {
            delete ts;
            return nullptr;
        }
        data = ts;
    }
    return reinterpret_cast<ThreadState*>(data);
}

static std::string jsonEscape(const std::string& s) {
    std::ostringstream out;
    for (unsigned char c : s) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[7];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned int>(c));
                    out << buf;
                } else out << static_cast<char>(c);
        }
    }
    return out.str();
}

static std::string jstringValue(JNIEnv* jni, jstring value) {
    if (!jni || !value || !g_stringContentsEnabled) return {};
    const char* utf = jni->GetStringUTFChars(value, nullptr);
    if (!utf) {
        if (jni->ExceptionCheck()) jni->ExceptionClear();
        return {};
    }
    std::string out(utf);
    jni->ReleaseStringUTFChars(value, utf);
    bool truncated = false;
    if (g_stringLimit >= 0 && static_cast<int>(out.size()) > g_stringLimit) {
        out.resize(static_cast<size_t>(g_stringLimit));
        truncated = true;
    }
    if (truncated) out += "...";
    return out;
}

static long long objectTag(jobject obj) {
    if (!obj || !g_jvmti || !g_tagsEnabled || !g_haveTagCapability) return 0;
    jlong tag = 0;
    if (g_jvmti->GetTag(obj, &tag) != JVMTI_ERROR_NONE) return 0;
    if (tag == 0) {
        tag = static_cast<jlong>(++g_nextObjectTag);
        if (g_jvmti->SetTag(obj, tag) != JVMTI_ERROR_NONE) return 0;
    }
    return static_cast<long long>(tag);
}

static std::string objectClassName(JNIEnv* jni, jobject obj) {
    if (!jni || !obj || !g_jvmti) return {};
    jclass cls = jni->GetObjectClass(obj);
    if (!cls) {
        if (jni->ExceptionCheck()) jni->ExceptionClear();
        return {};
    }
    char* sig = nullptr;
    char* gen = nullptr;
    std::string name;
    if (g_jvmti->GetClassSignature(cls, &sig, &gen) == JVMTI_ERROR_NONE) name = classNameFromSignature(sig);
    dealloc(reinterpret_cast<unsigned char*>(sig));
    dealloc(reinterpret_cast<unsigned char*>(gen));
    jni->DeleteLocalRef(cls);
    return name;
}

static std::string objectSummary(JNIEnv* jni, jobject obj, const std::string& declared) {
    if (!obj) return std::string("{\"kind\":\"object\",\"declared\":\"") + jsonEscape(declared) + "\",\"value\":null}";
    std::string cls = objectClassName(jni, obj);
    long long tag = objectTag(obj);
    std::ostringstream ss;
    ss << "{\"kind\":\"object\",\"declared\":\"" << jsonEscape(declared) << "\"";
    if (!cls.empty()) ss << ",\"class\":\"" << jsonEscape(cls) << "\"";
    if (tag != 0) ss << ",\"tag\":" << tag;
    if (cls == "java.lang.String" && g_stringContentsEnabled) {
        std::string val = jstringValue(jni, reinterpret_cast<jstring>(obj));
        ss << ",\"string\":\"" << jsonEscape(val) << "\"";
    }
    ss << "}";
    return ss.str();
}

struct DescriptorType {
    char kind = 0;
    std::string text;
    int slots = 1;
};

static bool parseOneType(const std::string& d, size_t& p, DescriptorType& out, bool allowVoid=false) {
    if (p >= d.size()) return false;
    size_t start = p;
    char c = d[p++];
    out.kind = c;
    out.slots = (c == 'J' || c == 'D') ? 2 : 1;
    if (c == 'V') {
        if (!allowVoid) return false;
    } else if (c == 'L') {
        size_t semi = d.find(';', p);
        if (semi == std::string::npos) return false;
        p = semi + 1;
    } else if (c == '[') {
        while (p < d.size() && d[p] == '[') ++p;
        if (p >= d.size()) return false;
        if (d[p] == 'L') {
            ++p;
            size_t semi = d.find(';', p);
            if (semi == std::string::npos) return false;
            p = semi + 1;
        } else {
            char ec = d[p++];
            if (std::string("BCDFIJSZ").find(ec) == std::string::npos) return false;
        }
    } else if (std::string("BCDFIJSZ").find(c) == std::string::npos) {
        return false;
    }
    out.text = d.substr(start, p-start);
    return true;
}

static std::vector<DescriptorType> argumentTypes(const std::string& descriptor) {
    std::vector<DescriptorType> out;
    size_t p = 0;
    if (descriptor.empty() || descriptor[p++] != '(') return out;
    while (p < descriptor.size() && descriptor[p] != ')') {
        DescriptorType t;
        if (!parseOneType(descriptor, p, t, false)) { out.clear(); return out; }
        out.push_back(t);
    }
    return out;
}

static DescriptorType returnType(const std::string& descriptor) {
    DescriptorType out;
    size_t close = descriptor.find(')');
    if (close == std::string::npos) return out;
    size_t p = close + 1;
    if (!parseOneType(descriptor, p, out, true)) return DescriptorType{};
    return out;
}

static std::string captureArguments(JNIEnv* jni, jthread thread, const Target& target) {
    if (!g_valuesEnabled) return "[]";
    if (!g_haveLocalVariableCapability) return "{\"status\":\"local_capability_unavailable\"}";
    auto types = argumentTypes(target.descriptor);
    std::ostringstream ss;
    ss << "[";
    jint slot = target.isStatic ? 0 : 1;
    for (size_t i = 0; i < types.size(); ++i) {
        if (i) ss << ",";
        const auto& t = types[i];
        ss << "{\"index\":" << i << ",\"slot\":" << slot << ",\"declared\":\"" << jsonEscape(t.text) << "\"";
        jvmtiError e = JVMTI_ERROR_NONE;
        switch (t.kind) {
            case 'L': case '[': {
                jobject obj = nullptr;
                e = g_jvmti->GetLocalObject(thread, 0, slot, &obj);
                if (e == JVMTI_ERROR_NONE) {
                    ss << ",\"value\":" << objectSummary(jni, obj, t.text);
                    if (obj) jni->DeleteLocalRef(obj);
                }
                break;
            }
            case 'J': {
                jlong v = 0; e = g_jvmti->GetLocalLong(thread, 0, slot, &v);
                if (e == JVMTI_ERROR_NONE) ss << ",\"value\":" << static_cast<long long>(v);
                break;
            }
            case 'F': {
                jfloat v = 0; e = g_jvmti->GetLocalFloat(thread, 0, slot, &v);
                if (e == JVMTI_ERROR_NONE) ss << ",\"value\":" << v;
                break;
            }
            case 'D': {
                jdouble v = 0; e = g_jvmti->GetLocalDouble(thread, 0, slot, &v);
                if (e == JVMTI_ERROR_NONE) ss << ",\"value\":" << v;
                break;
            }
            default: {
                jint v = 0; e = g_jvmti->GetLocalInt(thread, 0, slot, &v);
                if (e == JVMTI_ERROR_NONE) {
                    if (t.kind == 'Z') ss << ",\"value\":" << (v ? "true" : "false");
                    else ss << ",\"value\":" << v;
                }
                break;
            }
        }
        if (e != JVMTI_ERROR_NONE) ss << ",\"status\":\"" << jsonEscape(errorName(e)) << "\"";
        ss << "}";
        slot += t.slots;
    }
    ss << "]";
    return ss.str();
}

static std::string captureReturn(JNIEnv* jni, const Target& target, jboolean exceptional, jvalue value) {
    if (!g_valuesEnabled) return "";
    if (exceptional) return "{\"status\":\"exceptional_exit\"}";
    DescriptorType t = returnType(target.descriptor);
    if (!t.kind) return "{\"status\":\"bad_descriptor\"}";
    std::ostringstream ss;
    ss << "{\"declared\":\"" << jsonEscape(t.text) << "\"";
    switch (t.kind) {
        case 'V': ss << ",\"kind\":\"void\""; break;
        case 'L': case '[':
            ss << ",\"value\":" << objectSummary(jni, value.l, t.text); break;
        case 'J': ss << ",\"value\":" << static_cast<long long>(value.j); break;
        case 'F': ss << ",\"value\":" << value.f; break;
        case 'D': ss << ",\"value\":" << value.d; break;
        case 'Z': ss << ",\"value\":" << (value.z ? "true" : "false"); break;
        case 'B': ss << ",\"value\":" << static_cast<int>(value.b); break;
        case 'C': ss << ",\"value\":" << static_cast<unsigned int>(value.c); break;
        case 'S': ss << ",\"value\":" << static_cast<int>(value.s); break;
        default: ss << ",\"value\":" << value.i; break;
    }
    ss << "}";
    return ss.str();
}

static void logRecord(const char* event,
                      const Target* target,
                      jthread thread,
                      const std::string& receiverClass,
                      const std::string& receiverStatus,
                      long long receiverTag,
                      const std::string& arguments,
                      const std::string& returnValue,
                      const std::string& stack,
                      const std::string& detail,
                      int exceptional = 0) {
    std::lock_guard<std::mutex> lock(g_logMutex);
    if (!g_trace) return;
    unsigned long long seq = ++g_seq;
    ThreadState* ts = thread ? getThreadState(thread, true) : nullptr;
    std::string tn = thread ? threadName(thread) : "";
    std::fprintf(g_trace,
        "%llu\t%lld\t%lld\t%s\t%s\t%llu\t%s\t%s\t%s\t%s\t%s\t%s\t%lld\t%s\t%s\t%d\t%s\t%s\n",
        seq,
        epochMicros(),
        monotonicNanos(),
        event ? event : "",
        target ? tsvSafe(target->targetId).c_str() : "",
        ts ? ts->id : 0ULL,
        tsvSafe(tn).c_str(),
        target ? tsvSafe(target->owner).c_str() : "",
        target ? tsvSafe(target->method).c_str() : "",
        target ? tsvSafe(target->descriptor).c_str() : "",
        tsvSafe(receiverClass).c_str(),
        tsvSafe(receiverStatus).c_str(),
        receiverTag,
        tsvSafe(arguments).c_str(),
        tsvSafe(returnValue).c_str(),
        exceptional,
        tsvSafe(stack).c_str(),
        tsvSafe(detail).c_str());
    std::fflush(g_trace);
}

static std::pair<std::string,std::string> receiverClass(JNIEnv* jni, jthread thread, const Target& target) {
    if (!g_receiverEnabled) return {"", "disabled"};
    if (target.isStatic) return {"", "static"};
    if (!g_haveLocalVariableCapability) return {"", "capability_unavailable"};
    jobject obj = nullptr;
    jvmtiError e = g_jvmti->GetLocalObject(thread, 0, 0, &obj);
    if (e != JVMTI_ERROR_NONE) return {"", errorName(e)};
    if (!obj) return {"", "null"};
    jclass cls = jni->GetObjectClass(obj);
    if (!cls) {
        jni->DeleteLocalRef(obj);
        return {"", "GetObjectClass_failed"};
    }
    char* sig = nullptr;
    char* gen = nullptr;
    std::string name;
    e = g_jvmti->GetClassSignature(cls, &sig, &gen);
    if (e == JVMTI_ERROR_NONE) name = classNameFromSignature(sig);
    std::string status = e == JVMTI_ERROR_NONE ? "ok" : errorName(e);
    dealloc(reinterpret_cast<unsigned char*>(sig));
    dealloc(reinterpret_cast<unsigned char*>(gen));
    jni->DeleteLocalRef(cls);
    jni->DeleteLocalRef(obj);
    return {name, status};
}

static long long receiverTag(JNIEnv* jni, jthread thread, const Target& target) {
    if (!g_receiverEnabled || target.isStatic || !g_haveLocalVariableCapability || !g_tagsEnabled || !g_haveTagCapability) return 0;
    jobject obj = nullptr;
    jvmtiError e = g_jvmti->GetLocalObject(thread, 0, 0, &obj);
    if (e != JVMTI_ERROR_NONE || !obj) return 0;
    long long tag = objectTag(obj);
    jni->DeleteLocalRef(obj);
    return tag;
}

static void writeControlResult(const std::string& text) {
    if (g_controlPath.empty()) return;
    std::ofstream f(g_controlPath, std::ios::trunc | std::ios::binary);
    if (f) f << text << "\n";
}

static std::string readControl() {
    if (g_controlPath.empty()) return {};
    std::ifstream f(g_controlPath, std::ios::binary);
    if (!f) return {};
    std::string line;
    std::getline(f, line);
    return trim(line);
}

static void recordInstallStatus(Target& t, const std::string& detail) {
    logRecord("TARGET", &t, nullptr, "", "", 0, "", "", "", detail);
}

static bool installTargetOnClass(jclass klass, Target& target) {
    if (!g_jvmti || !klass || target.installed || target.traceBudgetExhausted) return target.installed;
    char* csig = nullptr;
    char* cgen = nullptr;
    if (g_jvmti->GetClassSignature(klass, &csig, &cgen) != JVMTI_ERROR_NONE) return false;
    std::string owner = classNameFromSignature(csig);
    dealloc(reinterpret_cast<unsigned char*>(csig));
    dealloc(reinterpret_cast<unsigned char*>(cgen));
    if (owner != target.owner) return false;

    jint count = 0;
    jmethodID* methods = nullptr;
    jvmtiError e = g_jvmti->GetClassMethods(klass, &count, &methods);
    if (e != JVMTI_ERROR_NONE) {
        recordInstallStatus(target, std::string("GetClassMethods failed: ") + errorName(e));
        return false;
    }
    bool matched = false;
    for (jint i = 0; i < count; ++i) {
        char* name = nullptr;
        char* sig = nullptr;
        char* gen = nullptr;
        e = g_jvmti->GetMethodName(methods[i], &name, &sig, &gen);
        if (e == JVMTI_ERROR_NONE && name && sig && target.method == name && target.descriptor == sig) {
            matched = true;
            jint mods = 0;
            g_jvmti->GetMethodModifiers(methods[i], &mods);
            target.isStatic = (mods & 0x0008) != 0;
            jlocation start = -1, end = -1;
            e = g_jvmti->GetMethodLocation(methods[i], &start, &end);
            if (e == JVMTI_ERROR_NONE && start >= 0) {
                e = g_jvmti->SetBreakpoint(methods[i], start);
                if (e == JVMTI_ERROR_NONE || e == JVMTI_ERROR_DUPLICATE) {
                    target.methodId = methods[i];
                    target.entryLocation = start;
                    target.installed = true;
                    recordInstallStatus(target, "breakpoint_installed@" + std::to_string(static_cast<long long>(start)));
                } else {
                    recordInstallStatus(target, std::string("SetBreakpoint failed: ") + errorName(e));
                }
            } else {
                recordInstallStatus(target, std::string("GetMethodLocation failed: ") + errorName(e));
            }
        }
        dealloc(reinterpret_cast<unsigned char*>(name));
        dealloc(reinterpret_cast<unsigned char*>(sig));
        dealloc(reinterpret_cast<unsigned char*>(gen));
        if (target.installed) break;
    }
    dealloc(reinterpret_cast<unsigned char*>(methods));
    if (!matched) recordInstallStatus(target, "class_loaded_method_not_found");
    return target.installed;
}

static bool inventoryMethodAllowed(const std::string& name) {
    return g_inventoryMethodNames.empty() ||
           std::find(g_inventoryMethodNames.begin(), g_inventoryMethodNames.end(), name) != g_inventoryMethodNames.end();
}

static void inventoryClass(jclass klass) {
    if (!g_jvmti || !klass || !g_traceEnabled || g_inventoryOwners.empty() || !g_started.load()) return;
    char* csig = nullptr;
    char* cgen = nullptr;
    if (g_jvmti->GetClassSignature(klass, &csig, &cgen) != JVMTI_ERROR_NONE) return;
    const std::string owner = classNameFromSignature(csig);
    dealloc(reinterpret_cast<unsigned char*>(csig));
    dealloc(reinterpret_cast<unsigned char*>(cgen));
    if (std::find(g_inventoryOwners.begin(), g_inventoryOwners.end(), owner) == g_inventoryOwners.end()) return;
    {
        std::lock_guard<std::mutex> lock(g_stateMutex);
        if (!g_inventoryDone.insert(owner).second) return;
    }
    jint count = 0;
    jmethodID* methods = nullptr;
    jvmtiError error = g_jvmti->GetClassMethods(klass, &count, &methods);
    Target marker;
    marker.targetId = "inventory." + owner;
    marker.owner = owner;
    if (error != JVMTI_ERROR_NONE) {
        logRecord("INVENTORY_ERROR", &marker, nullptr, "", "", 0, "", "", "", errorName(error));
        return;
    }
    int emitted = 0;
    for (jint i = 0; i < count; ++i) {
        char* name = nullptr;
        char* sig = nullptr;
        char* gen = nullptr;
        error = g_jvmti->GetMethodName(methods[i], &name, &sig, &gen);
        if (error == JVMTI_ERROR_NONE && name && sig && inventoryMethodAllowed(name)) {
            marker.method = name;
            marker.descriptor = sig;
            jint modifiers = 0;
            g_jvmti->GetMethodModifiers(methods[i], &modifiers);
            std::ostringstream details;
            details << "declared_owner=" << owner << ";modifiers=" << modifiers
                    << ";static=" << ((modifiers & 0x0008) ? 1 : 0)
                    << ";native=" << ((modifiers & 0x0100) ? 1 : 0);
            logRecord("METHOD_INVENTORY", &marker, nullptr, "", "", 0, "", "", "", details.str());
            ++emitted;
        }
        dealloc(reinterpret_cast<unsigned char*>(name));
        dealloc(reinterpret_cast<unsigned char*>(sig));
        dealloc(reinterpret_cast<unsigned char*>(gen));
    }
    dealloc(reinterpret_cast<unsigned char*>(methods));
    marker.method.clear();
    marker.descriptor.clear();
    logRecord("INVENTORY_CLASS", &marker, nullptr, "", "", 0, "", "", "",
              "declared_methods=" + std::to_string(count) + ";emitted=" + std::to_string(emitted));
}

static void installForClass(jclass klass) {
    if (!g_jvmti || !klass || !g_started.load()) return;
    std::lock_guard<std::mutex> lock(g_stateMutex);
    for (auto& t : g_targets) {
        if (!t.installed) installTargetOnClass(klass, t);
    }
}

static void installOnLoadedClasses() {
    if (!g_jvmti) return;
    jint count = 0;
    jclass* classes = nullptr;
    jvmtiError e = g_jvmti->GetLoadedClasses(&count, &classes);
    if (e != JVMTI_ERROR_NONE) {
        logRecord("ERROR", nullptr, nullptr, "", "", 0, "", "", "", std::string("GetLoadedClasses failed: ") + errorName(e));
        return;
    }
    {
        std::lock_guard<std::mutex> lock(g_stateMutex);
        for (jint i = 0; i < count; ++i) {
            for (auto& t : g_targets) {
                if (!t.installed) installTargetOnClass(classes[i], t);
            }
        }
    }
    for (jint i = 0; i < count; ++i) inventoryClass(classes[i]);
    dealloc(reinterpret_cast<unsigned char*>(classes));
}

static void stopTracing() {
    if (!g_jvmti) return;
    g_started.store(false);
    {
        std::lock_guard<std::mutex> lock(g_stateMutex);
        for (auto& t : g_targets) {
            if (t.installed && t.methodId) {
                g_jvmti->ClearBreakpoint(t.methodId, t.entryLocation);
                t.installed = false;
            }
        }
    }
    jint count = 0;
    jthread* threads = nullptr;
    if (g_jvmti->GetAllThreads(&count, &threads) == JVMTI_ERROR_NONE) {
        for (jint i = 0; i < count; ++i) {
            g_jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, threads[i]);
            ThreadState* ts = getThreadState(threads[i], false);
            if (ts) ts->active.clear();
        }
        dealloc(reinterpret_cast<unsigned char*>(threads));
    }
    logRecord("CONTROL", nullptr, nullptr, "", "", 0, "", "", "", "stopped");
}

static void startTracing() {
    if (!g_jvmti) return;
    g_started.store(true);
    installOnLoadedClasses();
    logRecord("CONTROL", nullptr, nullptr, "", "", 0, "", "", "", "started");
}

// R9.6 experimental, read-only state probe for selection membership.
// The original mod and the Bitwig JAR are unchanged. Only two static
// properties (the selected model List and its object identities) are read,
// at the entry/exit of the mod's existing selection-handler trace target.
// No UI repaint, event dispatch, or list mutation is performed here.
static bool isDeviceSelectionStateProbe(const Target* target) {
    return target && target->targetId ==
        "mod.device-selection-across-tracks.trace.diag-selection-core-pointer";
}
static std::string deviceSelectionState(JNIEnv* jni) {
    if (!jni) return "jni_null";
    jclass cls = jni->FindClass("com/bitwig/hacks/BrowserDeviceExactSelectionSetV160");
    if (!cls) {
        if (jni->ExceptionCheck()) jni->ExceptionClear();
        return "selection_class_unavailable";
    }
    jfieldID field = jni->GetStaticFieldID(cls, "SELECTED", "Ljava/util/List;");
    if (!field) {
        if (jni->ExceptionCheck()) jni->ExceptionClear();
        jni->DeleteLocalRef(cls);
        return "selection_field_unavailable";
    }
    jobject list = jni->GetStaticObjectField(cls, field);
    jni->DeleteLocalRef(cls);
    if (!list) return "selection_list_null";
    jclass listType = jni->FindClass("java/util/List");
    if (!listType) {
        if (jni->ExceptionCheck()) jni->ExceptionClear();
        jni->DeleteLocalRef(list);
        return "list_class_unavailable";
    }
    jmethodID sizeMethod = jni->GetMethodID(listType, "size", "()I");
    jmethodID getMethod = jni->GetMethodID(listType, "get", "(I)Ljava/lang/Object;");
    if (!sizeMethod || !getMethod) {
        if (jni->ExceptionCheck()) jni->ExceptionClear();
        jni->DeleteLocalRef(listType);jni->DeleteLocalRef(list);
        return "list_method_unavailable";
    }
    jint count = jni->CallIntMethod(list, sizeMethod);
    if (jni->ExceptionCheck()) {
        jni->ExceptionClear();
        jni->DeleteLocalRef(listType);jni->DeleteLocalRef(list);
        return "list_size_failed";
    }
    std::string result = "selection_size=" + std::to_string(count) + ";model_tags=";
    for (jint i=0;i<count && i<20;i++) {
        jobject model = jni->CallObjectMethod(list,getMethod,i);
        if (jni->ExceptionCheck()) {jni->ExceptionClear(); result += "[get_failed]"; break;}
        if (i>0) result += ",";
        result += std::to_string(objectTag(model));
        if (model) jni->DeleteLocalRef(model);
    }
    if (count>20) result += ",...";
    jni->DeleteLocalRef(listType);jni->DeleteLocalRef(list);
    return result;
}

// R9.7: read-only state of the older mod's independent view registries.
// This deliberately does not call any Bitwig painting or selection methods.
// Only requested on completion of the raw-pointer dispatch method, so it
// cannot create a per-frame overhead on device view repaint.
static std::string visualStaticMap(JNIEnv* jni, const char* className,
                                  const char* fieldName) {
    jclass klass = jni->FindClass(className);
    if (!klass) {
        if (jni->ExceptionCheck()) jni->ExceptionClear();
        return "class_unavailable";
    }
    jfieldID fld = jni->GetStaticFieldID(klass, fieldName, "Ljava/util/IdentityHashMap;");
    if (!fld) {
        if (jni->ExceptionCheck()) jni->ExceptionClear();
        jni->DeleteLocalRef(klass);
        return "field_unavailable";
    }
    jobject map = jni->GetStaticObjectField(klass, fld);
    jni->DeleteLocalRef(klass);
    if (!map) return "null";
    jclass mapClass = jni->FindClass("java/util/Map");
    jclass setClass = jni->FindClass("java/util/Set");
    jclass iteratorClass = jni->FindClass("java/util/Iterator");
    jclass entryClass = jni->FindClass("java/util/Map$Entry");
    if (!mapClass || !setClass || !iteratorClass || !entryClass) {
        if (jni->ExceptionCheck()) jni->ExceptionClear();
        if(mapClass) jni->DeleteLocalRef(mapClass);
        if(setClass) jni->DeleteLocalRef(setClass);
        if(iteratorClass) jni->DeleteLocalRef(iteratorClass);
        if(entryClass) jni->DeleteLocalRef(entryClass);
        jni->DeleteLocalRef(map);
        return "map_interfaces_unavailable";
    }
    jmethodID sizeId=jni->GetMethodID(mapClass,"size","()I");
    jmethodID entrySetId=jni->GetMethodID(mapClass,"entrySet","()Ljava/util/Set;");
    jmethodID iteratorId=jni->GetMethodID(setClass,"iterator","()Ljava/util/Iterator;");
    jmethodID hasNextId=jni->GetMethodID(iteratorClass,"hasNext","()Z");
    jmethodID nextId=jni->GetMethodID(iteratorClass,"next","()Ljava/lang/Object;");
    jmethodID keyId=jni->GetMethodID(entryClass,"getKey","()Ljava/lang/Object;");
    jmethodID valueId=jni->GetMethodID(entryClass,"getValue","()Ljava/lang/Object;");
    std::string result="method_unavailable";
    if(sizeId && entrySetId && iteratorId && hasNextId && nextId && keyId && valueId) {
        jint n=jni->CallIntMethod(map,sizeId);
        if(!jni->ExceptionCheck()) {
            result="size="+std::to_string(n)+";tags=";
            jobject set=jni->CallObjectMethod(map,entrySetId);
            jobject iter=set?jni->CallObjectMethod(set,iteratorId):nullptr;
            for(jint i=0;iter && i<n && i<12 && !jni->ExceptionCheck();i++) {
                if(!jni->CallBooleanMethod(iter,hasNextId))break;
                jobject entry=jni->CallObjectMethod(iter,nextId);
                jobject key=entry?jni->CallObjectMethod(entry,keyId):nullptr;
                jobject value=entry?jni->CallObjectMethod(entry,valueId):nullptr;
                if(i>0)result+=",";
                result+=std::to_string(objectTag(key))+":"+std::to_string(objectTag(value));
                if(key) jni->DeleteLocalRef(key);
                if(value) jni->DeleteLocalRef(value);
                if(entry) jni->DeleteLocalRef(entry);
            }
            if(n>12)result+=",...";
            if(iter)jni->DeleteLocalRef(iter);
            if(set)jni->DeleteLocalRef(set);
        }
    }
    if(jni->ExceptionCheck()) {jni->ExceptionClear();result="map_read_exception";}
    jni->DeleteLocalRef(entryClass);
    jni->DeleteLocalRef(iteratorClass);
    jni->DeleteLocalRef(setClass);
    jni->DeleteLocalRef(mapClass);
    jni->DeleteLocalRef(map);
    return result;
}

static std::string visualStaticSelected(JNIEnv* jni, const char* className) {
    jclass klass=jni->FindClass(className);
    if(!klass) {
        if(jni->ExceptionCheck())jni->ExceptionClear();
        return "class_unavailable";
    }
    jfieldID fld=jni->GetStaticFieldID(klass,"SELECTED","Ljava/util/List;");
    if(!fld) {
        if(jni->ExceptionCheck())jni->ExceptionClear();
        jni->DeleteLocalRef(klass);
        return "field_unavailable";
    }
    jobject list=jni->GetStaticObjectField(klass,fld);
    jni->DeleteLocalRef(klass);
    if(!list)return "null";
    jclass listClass=jni->FindClass("java/util/List");
    if(!listClass) {
        if(jni->ExceptionCheck())jni->ExceptionClear();
        jni->DeleteLocalRef(list);
        return "class_unavailable";
    }
    jmethodID sizeId=jni->GetMethodID(listClass,"size","()I");
    jmethodID getId=jni->GetMethodID(listClass,"get","(I)Ljava/lang/Object;");
    std::string result="method_unavailable";
    if(sizeId && getId) {
        jint count=jni->CallIntMethod(list,sizeId);
        if(!jni->ExceptionCheck()){
            result="size="+std::to_string(count)+";tags=";
            for(int i=0;i<count && i<12;i++){
                jobject device=jni->CallObjectMethod(list,getId,i);
                if(jni->ExceptionCheck())break;
                if(i>0)result+=",";
                result+=std::to_string(objectTag(device));
                if(device)jni->DeleteLocalRef(device);
            }
        }
    }
    if(jni->ExceptionCheck()){jni->ExceptionClear();result="list_read_exception";}
    jni->DeleteLocalRef(listClass);
    jni->DeleteLocalRef(list);
    return result;
}

static std::string visualProjectionState(JNIEnv* jni) {
    const char* direct="com/bitwig/hacks/BrowserDeviceDirectViewFieldV164";
    const char* gate="com/bitwig/hacks/BrowserDevicePaintGateOverrideV171";
    std::string result="direct_selected={" + visualStaticSelected(jni,direct) + "}";
    result+=";direct_views={" + visualStaticMap(jni,direct,"VIEWS") + "}";
    result+=";gate_selected={" + visualStaticSelected(jni,gate) + "}";
    result+=";gate_views={" + visualStaticMap(jni,gate,"VIEWS") + "}";
    result+=";gate_targets={" + visualStaticMap(jni,gate,"VIEW_TARGETS") + "}";
    return result;
}

static bool isVisualPointerCompletion(const Target* target) {
    return target && target->targetId ==
         "mod.device-selection-across-tracks.target.ui.input_dispatcher.dispatch_raw_pointer_event";
}

static void JNICALL cbBreakpoint(jvmtiEnv*, JNIEnv* jni, jthread thread, jmethodID method, jlocation) {
    if (!g_started.load() || g_shuttingDown.load()) return;
    Target* target = nullptr;
    bool exhaustedNow = false;
    jvmtiError budgetClearError = JVMTI_ERROR_NONE;
    {
        std::lock_guard<std::mutex> lock(g_stateMutex);
        target = findTargetByMethod(method);
        if(target && !target->traceBudgetExhausted) {
            ++target->entriesTraced;
            if(g_traceEntryLimit > 0 &&
               target->entriesTraced >= static_cast<unsigned long long>(g_traceEntryLimit)) {
                // Stop tracing this hot method after its final recorded entry.
                // The breakpoint is diagnostic-only; patched Java behavior
                // remains installed in the class independently.
                budgetClearError = g_jvmti->ClearBreakpoint(target->methodId, target->entryLocation);
                if(budgetClearError == JVMTI_ERROR_NONE || budgetClearError == JVMTI_ERROR_NOT_FOUND) {
                    target->traceBudgetExhausted = true;
                    target->installed = false;
                    exhaustedNow = true;
                }
            }
        }
    }
    if (!target || (target->traceBudgetExhausted && !exhaustedNow)) return;
    if (isDeviceSelectionStateProbe(target))
        logRecord("SELECTION_STATE", target, thread, "", "", 0, "", "", "",
                  "before;" + deviceSelectionState(jni));

    ThreadState* ts = getThreadState(thread, true);
    if (!ts) return;
    auto recv = receiverClass(jni, thread, *target);
    long long recvTag = receiverTag(jni, thread, *target);
    std::string args = captureArguments(jni, thread, *target);
    ActiveTarget active;
    active.method = method;
    active.receiverClass = recv.first;
    active.receiverStatus = recv.second;
    active.receiverTag = recvTag;
    ts->active.push_back(active);

    // Only this thread pays MethodExit overhead while inside an allow-listed target.
    g_jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_EXIT, thread);
    logRecord("ENTRY", target, thread, recv.first, recv.second, recvTag, args, "", stackContext(thread), "");
    if(exhaustedNow) {
        logRecord("CONTROL", target, thread, "", "", 0, "", "", "",
                  "trace_budget_reached=" + std::to_string(target->entriesTraced) +
                  "; diagnostic_breakpoint_cleared; BML_mod_hooks_unchanged");
    }
}

static void JNICALL cbMethodExit(jvmtiEnv*, JNIEnv* jni, jthread thread, jmethodID method,
                                 jboolean wasPoppedByException, jvalue returnValue) {
    if (g_shuttingDown.load()) return;
    ThreadState* ts = getThreadState(thread, false);
    if (!ts || ts->active.empty()) return;

    Target* target = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_stateMutex);
        target = findTargetByMethod(method);
    }
    if (!target) return; // non-target nested method exit while per-thread event is enabled
    if (isDeviceSelectionStateProbe(target))
        logRecord("SELECTION_STATE", target, thread, "", "", 0, "", "", "",
                  "after;" + deviceSelectionState(jni));
    if (isVisualPointerCompletion(target) && !wasPoppedByException)
        logRecord("VIEW_STATE", target, thread, "", "", 0, "", "", "",
                  visualProjectionState(jni));

    // Expected case is strict LIFO. If an unusual VM event breaks that assumption, fail closed
    // by locating the most recent matching target rather than fabricating pairing.
    int pos = -1;
    for (int i = static_cast<int>(ts->active.size()) - 1; i >= 0; --i) {
        if (ts->active[static_cast<size_t>(i)].method == method) { pos = i; break; }
    }
    if (pos < 0) {
        logRecord("ERROR", target, thread, "", "", 0, "", "", stackContext(thread), "target exit without active entry", wasPoppedByException ? 1 : 0);
        return;
    }
    ActiveTarget active = ts->active[static_cast<size_t>(pos)];
    bool lifo = (pos == static_cast<int>(ts->active.size()) - 1);
    ts->active.erase(ts->active.begin() + pos);
    std::string ret = captureReturn(jni, *target, wasPoppedByException, returnValue);
    logRecord("EXIT", target, thread, active.receiverClass, active.receiverStatus, active.receiverTag, "", ret,
              stackContext(thread), lifo ? "" : "non_lifo_pairing", wasPoppedByException ? 1 : 0);
    if (ts->active.empty()) {
        g_jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, thread);
    }
}

static void JNICALL cbClassFileLoadHook(jvmtiEnv*, JNIEnv*, jclass, jobject, const char* name,
                                            jobject, jint class_data_len,
                                            const unsigned char* class_data,
                                            jint* new_class_data_len,
                                            unsigned char** new_class_data) {
    if (!classHookEnabled() || g_shuttingDown.load() || !name) return;
    std::string owner(name);
    std::replace(owner.begin(), owner.end(), '/', '.');
    Target* target = findTargetByOwnerName(owner);
    const ReplacementClass* mappedReplacement = nullptr;
    if (g_classHookMode == "file" && !g_replacementClasses.empty()) {
        auto it = g_replacementClasses.find(owner);
        if (it != g_replacementClasses.end()) mappedReplacement = &it->second;
    }
    if (!target && !mappedReplacement) return;
    // Trace scope and replacement scope are independent. In legacy single-file mode,
    // only replacement_owner receives bytes. In replacement-map mode, only owners
    // explicitly present in the map receive their corresponding class bytes.
    if (g_classHookMode == "file" && g_replacementClasses.empty() && !g_replacementOwner.empty() && owner != g_replacementOwner) return;
    if (g_classHookMode == "file" && !g_replacementClasses.empty() && !mappedReplacement) return;

    if (new_class_data_len) *new_class_data_len = 0;
    if (new_class_data) *new_class_data = nullptr;

    unsigned long long originalHash = fnv1a64(class_data, class_data_len);
    jint replacementLen = 0;
    unsigned long long replacementHash = 0;
    const char* status = "original";

    if (g_classHookMode == "copy") {
        if (!g_jvmti || class_data_len <= 0 || !class_data || !new_class_data_len || !new_class_data) {
            status = "copy_invalid_callback_state";
        } else {
            unsigned char* replacement = nullptr;
            jvmtiError alloc = g_jvmti->Allocate(static_cast<jlong>(class_data_len), &replacement);
            if (alloc == JVMTI_ERROR_NONE && replacement) {
                std::memcpy(replacement, class_data, static_cast<size_t>(class_data_len));
                replacementLen = class_data_len;
                replacementHash = fnv1a64(replacement, replacementLen);
                *new_class_data_len = replacementLen;
                *new_class_data = replacement;
                status = (replacementHash == originalHash) ? "copy_identical" : "copy_hash_mismatch";
            } else {
                status = "copy_allocate_failed";
            }
        }
    } else if (g_classHookMode == "file") {
        const std::vector<unsigned char>* sourceBytes = nullptr;
        if (mappedReplacement) sourceBytes = &mappedReplacement->bytes;
        else if (!g_replacementClassBytes.empty()) sourceBytes = &g_replacementClassBytes;
        if (!g_jvmti || !sourceBytes || sourceBytes->empty() || !new_class_data_len || !new_class_data) {
            status = "file_invalid_callback_state";
        } else {
            unsigned char* replacement = nullptr;
            replacementLen = static_cast<jint>(sourceBytes->size());
            jvmtiError alloc = g_jvmti->Allocate(static_cast<jlong>(replacementLen), &replacement);
            if (alloc == JVMTI_ERROR_NONE && replacement) {
                std::memcpy(replacement, sourceBytes->data(), sourceBytes->size());
                replacementHash = fnv1a64(replacement, replacementLen);
                *new_class_data_len = replacementLen;
                *new_class_data = replacement;
                status = mappedReplacement ? "map_replacement" : "file_replacement";
            } else {
                replacementLen = 0;
                status = "file_allocate_failed";
            }
        }
    }

    ++g_classHookHits;
    std::ostringstream detail;
    detail << "mode=" << g_classHookMode
           << ";class=" << owner
           << ";bytes=" << class_data_len
           << ";fnv1a64=" << std::hex << originalHash << std::dec
           << ";replacement_bytes=" << replacementLen;
    if (replacementLen > 0) {
        detail << ";replacement_fnv1a64=" << std::hex << replacementHash << std::dec;
    }
    detail << ";status=" << status;
    logRecord("CLASS_FILE_LOAD", target, nullptr, "", "", 0, "", "", "", detail.str());
}

static void JNICALL cbClassPrepare(jvmtiEnv*, JNIEnv*, jthread, jclass klass) {
    if (g_started.load() && !g_shuttingDown.load()) {
        inventoryClass(klass);
        installForClass(klass);
    }
}

static void JNICALL cbThreadEnd(jvmtiEnv*, JNIEnv*, jthread thread) {
    ThreadState* ts = getThreadState(thread, false);
    if (ts) {
        g_jvmti->SetThreadLocalStorage(thread, nullptr);
        delete ts;
    }
}

static void JNICALL cbDataDumpRequest(jvmtiEnv*) {
    std::string cmd = readControl();
    if (cmd == "stop") {
        stopTracing();
        writeControlResult("stopped");
    } else if (cmd == "start") {
        startTracing();
        writeControlResult("started");
    } else {
        size_t installed = 0;
        {
            std::lock_guard<std::mutex> lock(g_stateMutex);
            for (const auto& t : g_targets) if (t.installed) ++installed;
        }
        std::ostringstream ss;
        ss << "status started=" << (g_started.load() ? 1 : 0)
           << " installed=" << installed << "/" << g_targets.size()
           << " records=" << g_seq.load()
           << " class_hook_hits=" << g_classHookHits.load();
        logRecord("CONTROL", nullptr, nullptr, "", "", 0, "", "", "", ss.str());
        writeControlResult(ss.str());
    }
}

static void JNICALL cbVMInit(jvmtiEnv*, JNIEnv*, jthread) {
    // Agent_OnLoad runs before GetLoadedClasses is legal. ClassPrepare handles most
    // targets; this closes the small window for anything prepared before VMInit.
    if (g_started.load() && !g_shuttingDown.load()) installOnLoadedClasses();
    logRecord("VM_INIT", nullptr, nullptr, "", "", 0, "", "", "", "");
}

static void JNICALL cbVMDeath(jvmtiEnv*, JNIEnv*) {
    g_shuttingDown.store(true);
    logRecord("VM_DEATH", nullptr, nullptr, "", "", 0, "", "", "", "");
    std::lock_guard<std::mutex> lock(g_logMutex);
    if (g_trace) { std::fflush(g_trace); std::fclose(g_trace); g_trace = nullptr; }
}

static bool parseTargets(const std::string& path, std::string& error) {
    std::ifstream f(path, std::ios::binary);
    if (!f) { error = "cannot open target config: " + path; return false; }
    std::string line;
    int lineNo = 0;
    while (std::getline(f, line)) {
        ++lineNo;
        if (!line.empty() && line.back() == '\r') line.pop_back();
        std::string t = trim(line);
        if (t.empty() || t[0] == '#') continue;
        auto cols = split(line, '\t');
        if (!cols.empty() && cols[0] == "target_id") continue;
        if (cols.size() < 4) {
            error = "target config line " + std::to_string(lineNo) + " needs at least 4 TSV columns";
            return false;
        }
        Target target;
        target.targetId = trim(cols[0]);
        target.owner = trim(cols[1]);
        target.method = trim(cols[2]);
        target.descriptor = trim(cols[3]);
        target.requestedOwner = cols.size() > 4 ? trim(cols[4]) : target.owner;
        target.notes = cols.size() > 5 ? trim(cols[5]) : "";
        if (target.targetId.empty() || target.owner.empty() || target.method.empty() || target.descriptor.empty()) {
            error = "target config line " + std::to_string(lineNo) + " contains an empty required field";
            return false;
        }
        g_targets.push_back(std::move(target));
    }
    if (g_targets.empty()) { error = "target config contains no targets"; return false; }
    return true;
}

static std::string moduleDirectory() {
#ifdef _WIN32
    HMODULE hm = nullptr;
    if (!GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCSTR>(&g_vm), &hm) || !hm) return {};
    char path[32768]{};
    DWORD n = GetModuleFileNameA(hm, path, static_cast<DWORD>(sizeof(path)));
    if (n == 0 || n >= sizeof(path)) return {};
    std::string s(path, n);
#else
    Dl_info info{};
    if (dladdr(reinterpret_cast<void*>(&g_vm), &info) == 0 || !info.dli_fname) return {};
    std::string s(info.dli_fname);
#endif
    size_t p = s.find_last_of("/\\");
    return p == std::string::npos ? std::string(".") : s.substr(0, p);
}

static void applyOption(const std::string& k0, const std::string& v0) {
    std::string k = trim(k0);
    std::string v = trim(v0);
    if (k == "config" || k == "targets") g_configPath = v;
    else if (k == "out") g_tracePath = v;
    else if (k == "control") g_controlPath = v;
    else if (k == "stack" && !v.empty()) g_stackDepth = (std::max)(0, (std::min)(16, std::atoi(v.c_str())));
    else if (k == "receiver") g_receiverEnabled = (v != "0" && v != "false");
    else if (k == "values") g_valuesEnabled = (v != "0" && v != "false");
    else if (k == "tags") g_tagsEnabled = (v != "0" && v != "false");
    else if (k == "strings") g_stringContentsEnabled = (v != "0" && v != "false");
    else if (k == "string_limit" && !v.empty()) g_stringLimit = (std::max)(0, std::atoi(v.c_str()));
    else if (k == "class_hook") g_classHookMode = (v == "noop" || v == "copy" || v == "file") ? v : "off";
    else if (k == "replacement_class") g_replacementClassPath = v;
    else if (k == "replacement_owner") g_replacementOwner = v;
    else if (k == "replacement_map") g_replacementMapPath = v;
    else if (k == "helper_jar") g_helperJarPath = v;
    else if (k == "inventory_classes") {
        g_inventoryOwners.clear();
        for (auto value : split(v, ',')) {
            value = trim(value);
            std::replace(value.begin(), value.end(), '/', '.');
            if (!value.empty() && g_inventoryOwners.size() < 12) g_inventoryOwners.push_back(value);
        }
    }
    else if (k == "inventory_methods") {
        g_inventoryMethodNames.clear();
        for (auto value : split(v, ',')) {
            value = trim(value);
            if (!value.empty() && g_inventoryMethodNames.size() < 24) g_inventoryMethodNames.push_back(value);
        }
    }
    else if (k == "trace_enabled") g_traceEnabled = (v != "0" && v != "false");
    else if (k == "trace_entry_limit" && !v.empty())
        g_traceEntryLimit = (std::max)(0, (std::min)(1000000, std::atoi(v.c_str())));
}

static bool parseSessionFile(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::string line;
    while (std::getline(f, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        std::string t = trim(line);
        if (t.empty() || t[0] == '#') continue;
        size_t eq = t.find('=');
        if (eq == std::string::npos) continue;
        applyOption(t.substr(0, eq), t.substr(eq + 1));
    }
    return true;
}

static void parseOptions(const char* options) {
    if (!options) return;
    std::string all = trim(options);
    if (all.empty()) return;
    // jcmd's JVMTI.agent_load treats '=' specially in its positional STRING parser on
    // some JDKs. The supported attach form is therefore a single session-file path.
    // Inline key=value options remain supported for -agentpath smoke tests.
    if (all.find('=') == std::string::npos && all.find(';') == std::string::npos) {
        if (parseSessionFile(all)) return;
    }
    for (const auto& raw : split(all, ';')) {
        size_t eq = raw.find('=');
        if (eq != std::string::npos) applyOption(raw.substr(0, eq), raw.substr(eq + 1));
    }
}

static jint initialize(JavaVM* vm, char* options) {
    g_vm = vm;
    std::fprintf(stderr, "[bitwig-jvmti] raw options: <%s>\n", options ? options : "<null>");
    parseOptions(options);
    std::string modDir = moduleDirectory();
    if (g_configPath.empty() && (!options || trim(options).empty()) && !modDir.empty()) {
        parseSessionFile(modDir + "/session.conf");
    }
    if (g_traceEnabled) {
        if (g_configPath.empty()) {
            std::fprintf(stderr, "[bitwig-jvmti] tracing enabled but no targets configured. Supply a session file or place session.conf next to the agent library.\n");
            return JNI_ERR;
        }
        if (g_tracePath.empty()) g_tracePath = modDir.empty() ? "bitwig-jvmti-trace.tsv" : modDir + "/bitwig-jvmti-trace.tsv";
        if (g_controlPath.empty()) g_controlPath = g_tracePath + ".control";

        std::string configError;
        if (!parseTargets(g_configPath, configError)) {
            std::fprintf(stderr, "[bitwig-jvmti] %s\n", configError.c_str());
            return JNI_ERR;
        }
    } else {
        g_targets.clear();
        std::fprintf(stderr, "[bitwig-jvmti] loader-only mode: exact tracing disabled\n");
    }

    jint envResult = vm->GetEnv(reinterpret_cast<void**>(&g_jvmti), JVMTI_VERSION_1_2);
    if (envResult != JNI_OK || !g_jvmti) {
        std::fprintf(stderr, "[bitwig-jvmti] GetEnv(JVMTI_VERSION_1_2) failed: %d\n", envResult);
        return JNI_ERR;
    }

    if (g_classHookMode == "file") {
        if (!g_replacementMapPath.empty()) {
            std::string replacementError;
            if (!loadReplacementMap(g_replacementMapPath, replacementError)) {
                std::fprintf(stderr, "[bitwig-jvmti] %s\n", replacementError.c_str());
                return JNI_ERR;
            }
            for (const auto& kv : g_replacementClasses) {
                unsigned long long replacementHash = fnv1a64(kv.second.bytes.data(), static_cast<jint>(kv.second.bytes.size()));
                std::fprintf(stderr, "[bitwig-jvmti] loaded mapped replacement: %s <- %s bytes=%zu fnv1a64=%llx\n",
                             kv.first.c_str(), kv.second.path.c_str(), kv.second.bytes.size(), replacementHash);
            }
        } else {
            if (g_replacementClassPath.empty() || !readBinaryFile(g_replacementClassPath, g_replacementClassBytes)) {
                std::fprintf(stderr, "[bitwig-jvmti] class_hook=file requires replacement_map or readable replacement_class bytes: %s\n",
                             g_replacementClassPath.c_str());
                return JNI_ERR;
            }
            unsigned long long replacementHash = fnv1a64(g_replacementClassBytes.data(), static_cast<jint>(g_replacementClassBytes.size()));
            std::fprintf(stderr, "[bitwig-jvmti] loaded replacement class: %s bytes=%zu fnv1a64=%llx\n",
                         g_replacementClassPath.c_str(), g_replacementClassBytes.size(), replacementHash);
        }
    }

    if (!g_helperJarPath.empty()) {
        jvmtiError helperErr = g_jvmti->AddToSystemClassLoaderSearch(g_helperJarPath.c_str());
        if (helperErr != JVMTI_ERROR_NONE) {
            std::fprintf(stderr, "[bitwig-jvmti] AddToSystemClassLoaderSearch failed for %s: %s (%d)\n",
                         g_helperJarPath.c_str(), errorName(helperErr), helperErr);
            return JNI_ERR;
        }
        std::fprintf(stderr, "[bitwig-jvmti] helper jar appended to system class loader search: %s\n",
                     g_helperJarPath.c_str());
    }

    jvmtiCapabilities potential{};
    g_jvmti->GetPotentialCapabilities(&potential);
    std::fprintf(stderr,
        "[bitwig-jvmti] potential caps: method_entry=%d method_exit=%d breakpoint=%d locals=%d tags=%d exception=%d frame_pop=%d retransform=%d all_class_hook=%d early_class_hook=%d\n",
        potential.can_generate_method_entry_events,
        potential.can_generate_method_exit_events,
        potential.can_generate_breakpoint_events,
        potential.can_access_local_variables,
        potential.can_tag_objects,
        potential.can_generate_exception_events,
        potential.can_generate_frame_pop_events,
        potential.can_retransform_classes,
        potential.can_generate_all_class_hook_events,
        potential.can_generate_early_class_hook_events);

    if (g_traceEnabled && (!potential.can_generate_breakpoint_events || !potential.can_generate_method_exit_events)) {
        std::fprintf(stderr,
            "[bitwig-jvmti] exact tracing capabilities are unavailable in the current JVMTI phase. "
            "Load this agent at JVM startup with -agentpath/JAVA_TOOL_OPTIONS; live jcmd attach cannot provide Breakpoint/MethodExit on this HotSpot build.\n");
        return JNI_ERR;
    }

    if (classHookEnabled() && !potential.can_generate_all_class_hook_events) {
        std::fprintf(stderr, "[bitwig-jvmti] class_hook requested but can_generate_all_class_hook_events is unavailable\n");
        return JNI_ERR;
    }

    jvmtiCapabilities requested{};
    if (g_traceEnabled) {
        requested.can_generate_breakpoint_events = 1;
        requested.can_generate_method_exit_events = 1;
        if ((g_receiverEnabled || g_valuesEnabled) && potential.can_access_local_variables) requested.can_access_local_variables = 1;
        if (g_tagsEnabled && potential.can_tag_objects) requested.can_tag_objects = 1;
    }
    if (classHookEnabled()) requested.can_generate_all_class_hook_events = 1;
    jvmtiError e = g_jvmti->AddCapabilities(&requested);
    if (e != JVMTI_ERROR_NONE) {
        std::fprintf(stderr, "[bitwig-jvmti] AddCapabilities failed: %s (%d)\n", errorName(e), e);
        return JNI_ERR;
    }
    jvmtiCapabilities have{};
    g_jvmti->GetCapabilities(&have);
    g_haveLocalVariableCapability = have.can_access_local_variables != 0;
    g_haveTagCapability = have.can_tag_objects != 0;
    g_haveAllClassHookCapability = have.can_generate_all_class_hook_events != 0;
    if (classHookEnabled() && !g_haveAllClassHookCapability) {
        std::fprintf(stderr, "[bitwig-jvmti] class-hook capability unavailable after capability request\n");
        return JNI_ERR;
    }

    if (g_traceEnabled && (!have.can_generate_breakpoint_events || !have.can_generate_method_exit_events)) {
        std::fprintf(stderr, "[bitwig-jvmti] required breakpoint/method-exit capabilities unavailable after capability request\n");
        return JNI_ERR;
    }

    jvmtiEventCallbacks cb{};
    if (g_traceEnabled) {
        cb.Breakpoint = &cbBreakpoint;
        cb.MethodExit = &cbMethodExit;
        cb.ClassPrepare = &cbClassPrepare;
        cb.ThreadEnd = &cbThreadEnd;
        cb.DataDumpRequest = &cbDataDumpRequest;
        cb.VMInit = &cbVMInit;
        cb.VMDeath = &cbVMDeath;
    }
    if (classHookEnabled()) cb.ClassFileLoadHook = &cbClassFileLoadHook;
    e = g_jvmti->SetEventCallbacks(&cb, sizeof(cb));
    if (e != JVMTI_ERROR_NONE) {
        std::fprintf(stderr, "[bitwig-jvmti] SetEventCallbacks failed: %s (%d)\n", errorName(e), e);
        return JNI_ERR;
    }

    // Open trace output only for an explicitly requested debug session.
    if (g_traceEnabled) {
#ifdef _WIN32
        fopen_s(&g_trace, g_tracePath.c_str(), "wb");
#else
        g_trace = std::fopen(g_tracePath.c_str(), "wb");
#endif
        if (!g_trace) {
            std::fprintf(stderr, "[bitwig-jvmti] cannot open trace output: %s\n", g_tracePath.c_str());
            return JNI_ERR;
        }
        std::fprintf(g_trace,
            "seq\tepoch_us\tmonotonic_ns\tevent\ttarget_id\tthread_id\tthread_name\towner\tmethod\tdescriptor\treceiver_class\treceiver_status\treceiver_tag\targuments\treturn_value\texit_exceptional\tstack\tdetail\n");
        std::fflush(g_trace);
    }

    if (classHookEnabled()) {
        e = g_jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr);
        if (e != JVMTI_ERROR_NONE) {
            std::fprintf(stderr, "[bitwig-jvmti] enabling ClassFileLoadHook failed: %s (%d)\n", errorName(e), e);
            return JNI_ERR;
        }
    }

    if (g_traceEnabled) {
        e = g_jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, nullptr);
        if (e != JVMTI_ERROR_NONE) return JNI_ERR;
        g_jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, nullptr);
        g_jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_THREAD_END, nullptr);
        g_jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_DATA_DUMP_REQUEST, nullptr);
        g_jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_DEATH, nullptr);
        if (g_startupMode) g_jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, nullptr);
    }

    g_started.store(g_traceEnabled);
    logRecord("AGENT_LOAD", nullptr, nullptr, "", "", 0, "", "", "",
              std::string("targets=") + std::to_string(g_targets.size()) +
              ";stack=" + std::to_string(g_stackDepth) +
              ";receiver=" + (g_receiverEnabled ? "1" : "0") +
              ";values=" + (g_valuesEnabled ? "1" : "0") +
              ";tags=" + (g_tagsEnabled ? "1" : "0") +
              ";local_cap=" + (g_haveLocalVariableCapability ? "1" : "0") +
              ";tag_cap=" + (g_haveTagCapability ? "1" : "0") +
              ";class_hook=" + g_classHookMode +
              ";class_hook_cap=" + (g_haveAllClassHookCapability ? "1" : "0") +
              ";replacement_class=" + g_replacementClassPath +
              ";replacement_owner=" + g_replacementOwner +
              ";replacement_map=" + g_replacementMapPath +
              ";replacement_count=" + std::to_string(g_replacementClasses.size()) +
              ";helper_jar=" + g_helperJarPath);
    jvmtiPhase phase = JVMTI_PHASE_ONLOAD;
    if (g_jvmti->GetPhase(&phase) == JVMTI_ERROR_NONE && phase == JVMTI_PHASE_LIVE) {
        installOnLoadedClasses();
    }

    size_t installed = 0;
    for (const auto& t : g_targets) if (t.installed) ++installed;
    std::ostringstream status;
    if (g_traceEnabled) {
        status << "loaded installed=" << installed << "/" << g_targets.size()
               << " trace=" << g_tracePath;
        writeControlResult(status.str());
    } else {
        status << "loaded loader_only=1 class_hook=" << g_classHookMode
               << " replacement_count=" << g_replacementClasses.size();
    }
    std::fprintf(stderr, "[bitwig-jvmti] %s\n", status.str().c_str());
    return JNI_OK;
}

} // namespace

AGENT_EXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void*) {
    g_startupMode = false;
    return initialize(vm, options);
}

// Exact tracing requires startup-phase capabilities on HotSpot 21. Bitwig is launched
// with this agent through JAVA_TOOL_OPTIONS/-agentpath; jcmd remains the control channel.
AGENT_EXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void*) {
    g_startupMode = true;
    return initialize(vm, options);
}

AGENT_EXPORT void JNICALL Agent_OnUnload(JavaVM*) {
    g_shuttingDown.store(true);
    std::lock_guard<std::mutex> lock(g_logMutex);
    if (g_trace) { std::fflush(g_trace); std::fclose(g_trace); g_trace = nullptr; }
}
