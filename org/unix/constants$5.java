// Generated by jextract

package org.unix;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import jdk.incubator.foreign.*;
import static jdk.incubator.foreign.ValueLayout.*;
class constants$5 {

    static final FunctionDescriptor vdprintf$FUNC = FunctionDescriptor.of(Constants$root.C_INT$LAYOUT,
        Constants$root.C_INT$LAYOUT,
        Constants$root.C_POINTER$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle vdprintf$MH = RuntimeHelper.downcallHandle(
        "vdprintf",
        constants$5.vdprintf$FUNC, false
    );
    static final FunctionDescriptor dprintf$FUNC = FunctionDescriptor.of(Constants$root.C_INT$LAYOUT,
        Constants$root.C_INT$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle dprintf$MH = RuntimeHelper.downcallHandle(
        "dprintf",
        constants$5.dprintf$FUNC, true
    );
    static final FunctionDescriptor fscanf$FUNC = FunctionDescriptor.of(Constants$root.C_INT$LAYOUT,
        Constants$root.C_POINTER$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle fscanf$MH = RuntimeHelper.downcallHandle(
        "fscanf",
        constants$5.fscanf$FUNC, true
    );
    static final FunctionDescriptor scanf$FUNC = FunctionDescriptor.of(Constants$root.C_INT$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle scanf$MH = RuntimeHelper.downcallHandle(
        "scanf",
        constants$5.scanf$FUNC, true
    );
    static final FunctionDescriptor sscanf$FUNC = FunctionDescriptor.of(Constants$root.C_INT$LAYOUT,
        Constants$root.C_POINTER$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle sscanf$MH = RuntimeHelper.downcallHandle(
        "sscanf",
        constants$5.sscanf$FUNC, true
    );
    static final FunctionDescriptor vfscanf$FUNC = FunctionDescriptor.of(Constants$root.C_INT$LAYOUT,
        Constants$root.C_POINTER$LAYOUT,
        Constants$root.C_POINTER$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle vfscanf$MH = RuntimeHelper.downcallHandle(
        "vfscanf",
        constants$5.vfscanf$FUNC, false
    );
}


