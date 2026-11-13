package dev.bitwigre.bml;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Windows: asks the Desktop Window Manager for the dark title bar (DWMWA_USE_IMMERSIVE_DARK_MODE)
 * on one window, through the Foreign Function API. Compiled with --release 22, the first release
 * where that API is final; {@link PatchbayWindow} reaches it by reflection, so on an older Java or
 * any failure the window keeps the normal title bar. The window is found by its title while that
 * title is unique: {@code apply} is called with a temporary one, before the window is shown.
 */
final class DarkTitleBar {
    private DarkTitleBar(){}
    /** 20 since Windows 10 20H1; 19 on the builds before it. */
    private static final int DARK_MODE=20,DARK_MODE_OLD=19;

    static boolean apply(String uniqueTitle) throws Throwable {
        Linker linker=Linker.nativeLinker();
        try(Arena arena=Arena.ofConfined()){
            SymbolLookup user32=SymbolLookup.libraryLookup("user32",arena);
            SymbolLookup dwm=SymbolLookup.libraryLookup("dwmapi",arena);
            MethodHandle find=linker.downcallHandle(user32.find("FindWindowW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.ADDRESS));
            MethodHandle set=linker.downcallHandle(dwm.find("DwmSetWindowAttribute").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.JAVA_INT));
            MemorySegment title=arena.allocateFrom(uniqueTitle,java.nio.charset.StandardCharsets.UTF_16LE);
            MemorySegment hwnd=(MemorySegment)find.invokeExact(MemorySegment.NULL,title);
            if(hwnd.address()==0)return false;
            MemorySegment on=arena.allocateFrom(ValueLayout.JAVA_INT,1);
            int result=(int)set.invokeExact(hwnd,DARK_MODE,on,4);
            if(result!=0)result=(int)set.invokeExact(hwnd,DARK_MODE_OLD,on,4);
            return result==0;
        }
    }
}
