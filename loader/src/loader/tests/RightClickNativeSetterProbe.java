import java.lang.reflect.Method;

/** Real 6.1.4 type, prepared ORIGINAL v1.1.0 helper, resolved BML target. */
public final class RightClickNativeSetterProbe {
    public static void main(String[] args) throws Exception {
        Class<?> nativePanel=Class.forName("com.bitwig.flt.widget.core.device.yPU",false,
                                              ClassLoader.getSystemClassLoader());
        Class<?> helper=Class.forName("com.bitwig.mods.remotepagebuttons.RemoteControlPageButtonsAlwaysVisible");
        Method find=helper.getDeclaredMethod("findEditorSetter",Class.class);
        find.setAccessible(true);
        Object result;
        try {
            result=find.invoke(null,nativePanel);
        } catch(java.lang.reflect.InvocationTargetException e) {
            throw new AssertionError("BML class owner lookup failed",e.getCause());
        }
        if(!(result instanceof Method method))throw new AssertionError("Unexpected result "+result);
        if(!method.getDeclaringClass().equals(nativePanel) ||
           !method.getName().equals("cfM") ||
           !method.getReturnType().equals(void.class) ||
           method.getParameterCount()!=1 || method.getParameterTypes()[0]!=boolean.class)
            throw new AssertionError("Wrong Bitwig 6.1.4 editor mode setter: "+method);
        System.out.println("R614_REMOTE_RIGHTCLICK_REFLECTION_PASS "+method);
    }
}
