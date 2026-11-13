#!/usr/bin/env python3
"""Candidate 17: verify that helper-requested BML_MOD_CONFIG keys fail closed.
Uses packaged helper jars and reflection only; does not load Bitwig or execute mods.
"""
import pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[2]
source='''
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
public class MissingBindingProbe {
    static Object newInstance(String name,Object... args) throws Exception {
        Class<?> type=Class.forName(name);
        for(Constructor<?> ctor:type.getDeclaredConstructors())if(ctor.getParameterCount()==args.length){
            ctor.setAccessible(true);return ctor.newInstance(args);
        }
        throw new AssertionError(name);
    }
    static List<?> scan(String jar, String... ids) throws Exception {
        Class<?> type=Class.forName("dev.bitwigre.bml.JavaPreparer$Mod");
        Constructor<?> ctor=type.getDeclaredConstructor();ctor.setAccessible(true);
        Object mod=ctor.newInstance();
        Field field=type.getDeclaredField("helperJar");field.setAccessible(true);field.set(mod,Path.of(jar));
        field=type.getDeclaredField("targets");field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,Object> targets=(Map<String,Object>)field.get(mod);
        for(String id:ids)targets.put(id,newInstance("dev.bitwigre.bml.JavaPreparer$Resolved","UNMAPPED",null,null));
        Class<?> preparer=Class.forName("dev.bitwigre.bml.JavaPreparer");
        Method method=preparer.getDeclaredMethod("missingHelperBindingReads",type);method.setAccessible(true);
        return (List<?>)method.invoke(null,mod);
    }
    public static void main(String[] a) throws Exception {
        List<?> bad=scan(a[0],"settings.shortcuts.column.set_index","settings.shortcuts.column.set_width","action.object.get_id");
        if(!bad.contains("settings.shortcuts.column.set_index")||!bad.contains("settings.shortcuts.column.set_width"))
            throw new AssertionError("Alias runtime dependency was not detected: "+bad);
        List<?> good=scan(a[1],"timeline.action.remove_time","action.object.base.icon_resource_storage");
        if(!good.isEmpty())throw new AssertionError("Unexpected Paste Time helper key: "+good);
        System.out.println("PASS: helper-requested unmapped alias keys are detected; unrelated Paste Time declarations remain optional");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='bml-binding-audit-') as td:
    folder=pathlib.Path(td); (folder/'MissingBindingProbe.java').write_text(source)
    app=root/'loader/app/bml-control.jar'
    def run(*cmd):
        p=subprocess.run([str(v) for v in cmd],text=True,capture_output=True)
        if p.returncode:raise AssertionError(f'{cmd}\n{p.stdout}\n{p.stderr}')
        return p.stdout
    run('javac','-cp',app,'-d',folder,folder/'MissingBindingProbe.java')
    print(run('java','-cp',f'{folder}:{app}','MissingBindingProbe',root/'mods/shortcut-aliases/helper.jar',root/'mods/paste-time/helper.jar').strip())
