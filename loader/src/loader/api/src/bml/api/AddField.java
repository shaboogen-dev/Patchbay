package bml.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds an instance field to a Bitwig class, on the {@code VarHandle} that reaches it.
 *
 * <pre>
 * final class Fields {
 *     &#64;AddField(target = cNI.class, name = "my_mod$count", type = int.class)
 *     static final VarHandle COUNT = handle("my_mod$count", int.class);
 *
 *     private static VarHandle handle(String name, Class&lt;?&gt; type) {
 *         try {
 *             return MethodHandles.privateLookupIn(cNI.class, MethodHandles.lookup()).findVarHandle(cNI.class, name, type);
 *         } catch (ReflectiveOperationException e) {
 *             throw new ExceptionInInitializerError(e);
 *         }
 *     }
 * }
 * </pre>
 *
 * The annotated field must be a {@code static final VarHandle}. The mod kit checks the declaration
 * against the Bitwig jar the mod is built against and writes it into the release's
 * {@code mod.json}, then removes the annotation from the shipped class.
 *
 * <p>The new field is public, transient and synthetic and starts at zero or null. The handle's
 * access mode decides the memory semantics of each access ({@code getVolatile}, {@code get}). Keep the handles
 * in a class of their own, so the hook class doesn't load the owner earlier than Bitwig does.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface AddField {
    /** The Bitwig class that gets the field. */
    Class<?> target();

    /** The field's name: the mod's ID with everything but letters and digits as {@code _}, then {@code $}, then a name. */
    String name();

    /** The field's type: a primitive, a JDK class, a Bitwig class, or an array of those. */
    Class<?> type();

    /** Field ID in the manifest. Default: the annotated field's name in kebab case. */
    String id() default "";
}
