// Generated by jextract

package org.unix;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import jdk.incubator.foreign.*;
import static jdk.incubator.foreign.ValueLayout.*;
class constants$11 {

    static final FunctionDescriptor fseeko$FUNC = FunctionDescriptor.of(Constants$root.C_INT$LAYOUT,
        Constants$root.C_POINTER$LAYOUT,
        Constants$root.C_LONG_LONG$LAYOUT,
        Constants$root.C_INT$LAYOUT
    );
    static final MethodHandle fseeko$MH = RuntimeHelper.downcallHandle(
        "fseeko",
        constants$11.fseeko$FUNC, false
    );
    static final FunctionDescriptor ftello$FUNC = FunctionDescriptor.of(Constants$root.C_LONG_LONG$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle ftello$MH = RuntimeHelper.downcallHandle(
        "ftello",
        constants$11.ftello$FUNC, false
    );
    static final FunctionDescriptor fgetpos$FUNC = FunctionDescriptor.of(Constants$root.C_INT$LAYOUT,
        Constants$root.C_POINTER$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle fgetpos$MH = RuntimeHelper.downcallHandle(
        "fgetpos",
        constants$11.fgetpos$FUNC, false
    );
    static final FunctionDescriptor fsetpos$FUNC = FunctionDescriptor.of(Constants$root.C_INT$LAYOUT,
        Constants$root.C_POINTER$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle fsetpos$MH = RuntimeHelper.downcallHandle(
        "fsetpos",
        constants$11.fsetpos$FUNC, false
    );
    static final FunctionDescriptor clearerr$FUNC = FunctionDescriptor.ofVoid(
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle clearerr$MH = RuntimeHelper.downcallHandle(
        "clearerr",
        constants$11.clearerr$FUNC, false
    );
    static final FunctionDescriptor feof$FUNC = FunctionDescriptor.of(Constants$root.C_INT$LAYOUT,
        Constants$root.C_POINTER$LAYOUT
    );
    static final MethodHandle feof$MH = RuntimeHelper.downcallHandle(
        "feof",
        constants$11.feof$FUNC, false
    );
}


