package org.konpeki;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.Set;

final class NoTaskbar {
    private static final int WH_CBT = 5;
    private static final int HCBT_CREATEWND = 3;
    private static final int GWL_STYLE = -16;
    private static final int GWL_EXSTYLE = -20;
    private static final long WS_CHILD = 0x40000000L;
    private static final long WS_EX_TOOLWINDOW = 0x80L;
    private static final long WS_EX_APPWINDOW = 0x40000L;
    private static final long WS_EX_NOACTIVATE = 0x08000000L;

    private static final Set<Integer> hooked = new HashSet<>();
    private static MethodHandle callNextHook, getWindowLong, setWindowLong;

    private NoTaskbar() {}

    // Call on the thread that creates the windows, once is enough per thread
    static synchronized void hookCurrentThread() {
        if (!System.getProperty("os.name").startsWith("Windows")) return;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            int thread = (int) linker.downcallHandle(
                            kernel32.find("GetCurrentThreadId").orElseThrow(), FunctionDescriptor.of(JAVA_INT))
                    .invokeExact();
            if (!hooked.add(thread)) return;

            callNextHook = linker.downcallHandle(
                    user32.find("CallNextHookEx").orElseThrow(),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
            getWindowLong = linker.downcallHandle(
                    user32.find("GetWindowLongPtrW").orElseThrow(),
                    FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT));
            setWindowLong = linker.downcallHandle(
                    user32.find("SetWindowLongPtrW").orElseThrow(),
                    FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG));
            MemorySegment hook = linker.upcallStub(
                    MethodHandles.lookup()
                            .findStatic(
                                    NoTaskbar.class,
                                    "onHook",
                                    MethodType.methodType(long.class, int.class, long.class, long.class)),
                    FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG),
                    Arena.global());
            MemorySegment installed = (MemorySegment) linker.downcallHandle(
                            user32.find("SetWindowsHookExW").orElseThrow(),
                            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
                    .invokeExact(WH_CBT, hook, MemorySegment.NULL, thread);
            if (installed.equals(MemorySegment.NULL)) hooked.remove(thread);
        } catch (Throwable e) {
            // Worst case the window shows on the taskbar
        }
    }

    // wParam is the new window's handle
    private static long onHook(int code, long wParam, long lParam) {
        try {
            if (code == HCBT_CREATEWND && ((long) getWindowLong.invokeExact(wParam, GWL_STYLE) & WS_CHILD) == 0) {
                long ex = (long) getWindowLong.invokeExact(wParam, GWL_EXSTYLE);
                long unused = (long) setWindowLong.invokeExact(
                        wParam, GWL_EXSTYLE, (ex | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE) & ~WS_EX_APPWINDOW);
            }
            return (long) callNextHook.invokeExact(MemorySegment.NULL, code, wParam, lParam);
        } catch (Throwable e) {
            return 0;
        }
    }
}
