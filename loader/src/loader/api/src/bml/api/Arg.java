package bml.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A {@link Hook} parameter that receives one of the target method's parameters, counting from 0. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface Arg {
    int value();
}
