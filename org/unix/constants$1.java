// Generated by jextract

package org.unix;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import jdk.incubator.foreign.*;
import static jdk.incubator.foreign.ValueLayout.*;
class constants$1 {

    static final FunctionDescriptor fclose$FUNC = FunctionDescriptor.of(Constants$root.C_INT$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle fclose$MH = RuntimeHelper.downcallHandle(
        "fclose",
        constants$1.fclose$FUNC, false
    );
    static final FunctionDescriptor tmpfile$FUNC = FunctionDescriptor.of(Constants$root.C_POINTER$LAYOUT);
    static final MethodHandle tmpfile$MH = RuntimeHelper.downcallHandle(
        "tmpfile",
        constants$1.tmpfile$FUNC, false
    );
    static final FunctionDescriptor tmpnam$FUNC = FunctionDescriptor.of(Constants$root.C_POINTER$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle tmpnam$MH = RuntimeHelper.downcallHandle(
        "tmpnam",
        constants$1.tmpnam$FUNC, false
    );
    static final FunctionDescriptor tmpnam_r$FUNC = FunctionDescriptor.of(Constants$root.C_POINTER$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle tmpnam_r$MH = RuntimeHelper.downcallHandle(
        "tmpnam_r",
        constants$1.tmpnam_r$FUNC, false
    );
    static final FunctionDescriptor tempnam$FUNC = FunctionDescriptor.of(Constants$root.C_POINTER$LAYOUT,
        Constants$root.C_POINTER$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle tempnam$MH = RuntimeHelper.downcallHandle(
        "tempnam",
        constants$1.tempnam$FUNC, false
    );
    static final FunctionDescriptor fflush$FUNC = FunctionDescriptor.of(Constants$root.C_INT$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle fflush$MH = RuntimeHelper.downcallHandle(
        "fflush",
        constants$1.fflush$FUNC, false
    );
}


