/*
 * JNI machine-dependent definitions for macOS (arm64 and x86_64, both LP64, clang), used to
 * build the tracer agent for macOS. jni.h and jvmti.h come from the pinned JDK (the same on
 * every platform); the JDK installed for builds is the Windows one, whose jni_md.h describes
 * Windows, so the macOS build takes this file instead. The definitions are the platform's JNI
 * ABI, the same as Linux x86-64's: jint is 32-bit, jlong 64-bit (long), jbyte signed 8-bit,
 * and exported symbols use default visibility with the platform's normal calling convention.
 */
#ifndef BML_JNI_MD_H
#define BML_JNI_MD_H

#if !defined(__APPLE__) || !defined(__LP64__) || !(defined(__arm64__) || defined(__x86_64__))
#error "this jni_md.h is for macOS arm64 / x86_64 only"
#endif

#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT __attribute__((visibility("default")))
#define JNICALL

typedef int jint;
typedef long jlong;
typedef signed char jbyte;

#endif
