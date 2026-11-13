package bml.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The first parameter of an {@link At#WRAP_RETURN} hook: the value Bitwig is about to return. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface Return {
}
