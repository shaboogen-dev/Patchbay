package bml.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the annotated method from inside a Bitwig method.
 *
 * <pre>
 * &#64;Hook(target = cNI.class, method = "eventOccurred", at = At.ENTRY)
 * public static void beforeInvoke(&#64;This cNI listener) { ... }
 * </pre>
 *
 * The annotated method must be {@code public static} and live in the mod's helper class. The mod
 * kit checks the hook against the Bitwig jar the mod is built against and writes it into the
 * release's {@code mod.json}, then removes the annotation from the shipped class.
 *
 * <p>What the method receives and returns depends on {@link #at()}:
 * <ul>
 * <li>{@link At#ENTRY}, {@link At#BEFORE_RETURN}: parameters marked {@link This} or {@link Arg};
 *     returns {@code void}. {@code BEFORE_RETURN} takes object parameters only.</li>
 * <li>{@link At#WRAP_RETURN}: first parameter marked {@link Return} (the value Bitwig is about to
 *     return), then {@link This} / {@link Arg} object parameters; the method's result is returned
 *     instead.</li>
 * <li>{@link At#REPLACE_CALL}: replaces one call inside the target method. Parameters are the
 *     call's receiver (unless the call is static) followed by the call's arguments, unmarked; the
 *     method returns what the call returns.</li>
 * <li>{@link At#OVERRIDE_AFTER_SUPER}, {@link At#OVERRIDE_BEFORE_SUPER}, {@link At#OVERRIDE_INSTEAD}:
 *     adds an override of an inherited method to {@link #target()}. Parameters as for
 *     {@code ENTRY}; returns {@code void}, except {@code OVERRIDE_INSTEAD}, which returns exactly
 *     what the overridden method returns.</li>
 * </ul>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Hook {
    /** The Bitwig class to patch. */
    Class<?> target();

    /**
     * The Bitwig method: its name, or name and JVM descriptor ({@code "vhT(Ljava/lang/String;)V"})
     * when the name alone is ambiguous. The build lists the candidates when it is.
     */
    String method();

    /** Where the hook runs. */
    At at();

    /** Hook ID in the manifest. Default: the annotated method's name in kebab case. */
    String id() default "";

    /** {@link At#REPLACE_CALL} only: the class named in the call instruction. */
    Class<?> callOwner() default void.class;

    /** {@link At#REPLACE_CALL} only: the called method's name, or name and descriptor. */
    String call() default "";

    /**
     * {@link At#REPLACE_CALL} only: which matching call to replace, counting from 1 in the order
     * the calls appear in the target method.
     */
    int occurrence() default 1;
}
