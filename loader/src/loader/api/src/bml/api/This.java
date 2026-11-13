package bml.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A {@link Hook} parameter that receives the object whose method is running. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface This {
}
