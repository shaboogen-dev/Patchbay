import java.lang.reflect.Method;

/** Exercise BML's generated adapter against unmodified Bitwig 6.1.4 classes. */
public class Native614BridgeProbe {
    public static void main(String[] args) throws Exception {
        String bridgeName = args[0];
        Class<?> bridge = Class.forName(bridgeName);
        Method getSourceName = bridge.getMethod("getMethodName",Method.class);
        Method getDeclared = bridge.getMethod("getDeclaredMethod",Class.class,String.class,Class[].class);
        Class<?> base = Class.forName("com.bitwig.flt.widget.core.device.htp", false,
                                      ClassLoader.getSystemClassLoader());
        Method viewResolver = null;
        for(Method candidate:base.getDeclaredMethods()) {
            if(candidate.getName().equals("cfM") && candidate.getParameterCount()==1 &&
               candidate.getParameterTypes()[0].getSimpleName().equals("jsd") &&
               candidate.getReturnType().getSimpleName().equals("gRj")) {
                viewResolver=candidate;break;
            }
        }
        if(viewResolver==null)throw new AssertionError("Target 6.1.4 htp.cfM(jsd):gRj unavailable");
        Object alias=getSourceName.invoke(null,viewResolver);
        if(!"vhT".equals(alias))throw new AssertionError("Expected vhT, got "+alias);
        Class<?> paintBase = Class.forName("com.bitwig.flt.widget.core.device.BVs", false,
                                           ClassLoader.getSystemClassLoader());
        Method paint = (Method)getDeclared.invoke(null,paintBase,"ER",new Class<?>[0]);
        if(!paint.getName().equals("MJs")||paint.getDeclaringClass()!=paintBase)
            throw new AssertionError("Inherited paint mapping invalid: "+paint);
        if(!"ER".equals(getSourceName.invoke(null,paint)))
            throw new AssertionError("Paint method source name not restored");
        System.out.println("NATIVE_614_BRIDGE_PASS: htp.cfM->vhT, BVs.MJs<->JkF.ER");
    }
}
