// Generated by jextract

package org.unix;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import jdk.incubator.foreign.*;
import static jdk.incubator.foreign.ValueLayout.*;
public class __mbstate_t {

    static final  GroupLayout $struct$LAYOUT = MemoryLayout.structLayout(
        Constants$root.C_INT$LAYOUT.withName("__count"),
        MemoryLayout.unionLayout(
            Constants$root.C_INT$LAYOUT.withName("__wch"),
            MemoryLayout.sequenceLayout(4, Constants$root.C_CHAR$LAYOUT).withName("__wchb")
        ).withName("__value")
    );
    public static MemoryLayout $LAYOUT() {
        return __mbstate_t.$struct$LAYOUT;
    }
    static final VarHandle __count$VH = $struct$LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("__count"));
    public static VarHandle __count$VH() {
        return __mbstate_t.__count$VH;
    }
    public static int __count$get(MemorySegment seg) {
        return (int)__mbstate_t.__count$VH.get(seg);
    }
    public static void __count$set( MemorySegment seg, int x) {
        __mbstate_t.__count$VH.set(seg, x);
    }
    public static int __count$get(MemorySegment seg, long index) {
        return (int)__mbstate_t.__count$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void __count$set(MemorySegment seg, long index, int x) {
        __mbstate_t.__count$VH.set(seg.asSlice(index*sizeof()), x);
    }
    public static class __value {

        static final  GroupLayout __value$union$LAYOUT = MemoryLayout.unionLayout(
            Constants$root.C_INT$LAYOUT.withName("__wch"),
            MemoryLayout.sequenceLayout(4, Constants$root.C_CHAR$LAYOUT).withName("__wchb")
        );
        public static MemoryLayout $LAYOUT() {
            return __value.__value$union$LAYOUT;
        }
        static final VarHandle __wch$VH = __value$union$LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("__wch"));
        public static VarHandle __wch$VH() {
            return __value.__wch$VH;
        }
        public static int __wch$get(MemorySegment seg) {
            return (int)__value.__wch$VH.get(seg);
        }
        public static void __wch$set( MemorySegment seg, int x) {
            __value.__wch$VH.set(seg, x);
        }
        public static int __wch$get(MemorySegment seg, long index) {
            return (int)__value.__wch$VH.get(seg.asSlice(index*sizeof()));
        }
        public static void __wch$set(MemorySegment seg, long index, int x) {
            __value.__wch$VH.set(seg.asSlice(index*sizeof()), x);
        }
        public static MemorySegment __wchb$slice(MemorySegment seg) {
            return seg.asSlice(0, 4);
        }
        public static long sizeof() { return $LAYOUT().byteSize(); }
        public static MemorySegment allocate(SegmentAllocator allocator) { return allocator.allocate($LAYOUT()); }
        public static MemorySegment allocateArray(int len, SegmentAllocator allocator) {
            return allocator.allocate(MemoryLayout.sequenceLayout(len, $LAYOUT()));
        }
        public static MemorySegment allocate(ResourceScope scope) { return allocate(SegmentAllocator.nativeAllocator(scope)); }
        public static MemorySegment allocateArray(int len, ResourceScope scope) {
            return allocateArray(len, SegmentAllocator.nativeAllocator(scope));
        }
        public static MemorySegment ofAddress(MemoryAddress addr, ResourceScope scope) { return RuntimeHelper.asArray(addr, $LAYOUT(), 1, scope); }
    }

    public static MemorySegment __value$slice(MemorySegment seg) {
        return seg.asSlice(4, 4);
    }
    public static long sizeof() { return $LAYOUT().byteSize(); }
    public static MemorySegment allocate(SegmentAllocator allocator) { return allocator.allocate($LAYOUT()); }
    public static MemorySegment allocateArray(int len, SegmentAllocator allocator) {
        return allocator.allocate(MemoryLayout.sequenceLayout(len, $LAYOUT()));
    }
    public static MemorySegment allocate(ResourceScope scope) { return allocate(SegmentAllocator.nativeAllocator(scope)); }
    public static MemorySegment allocateArray(int len, ResourceScope scope) {
        return allocateArray(len, SegmentAllocator.nativeAllocator(scope));
    }
    public static MemorySegment ofAddress(MemoryAddress addr, ResourceScope scope) { return RuntimeHelper.asArray(addr, $LAYOUT(), 1, scope); }
}


