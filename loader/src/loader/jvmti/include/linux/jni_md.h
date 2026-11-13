/*
 * JNI machine-dependent definitions for Linux x86-64 (LP64, GCC), used to build the tracer
 * agent for Linux. jni.h and jvmti.h come from the pinned JDK (the same on every platform);
 * the JDK installed for builds is the Windows one, whose jni_md.h describes Windows, so the
 * Linux build takes this file instead. The definitions are the platform's JNI ABI:
 * jint is 32-bit, jlong 64-bit, jbyte signed 8-bit, and exported symbols use default
 * visibility with the platform's normal calling convention.
 */
#ifndef BML_JNI_MD_H
#define BML_JNI_MD_H

#if !defined(__linux__) || !defined(__x86_64__) || !defined(__LP64__)
#error "this jni_md.h is for Linux x86-64 only"
#endif

#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT __attribute__((visibility("default")))
#define JNICALL

typedef int jint;
typedef long jlong;
typedef signed char jbyte;

#endif
