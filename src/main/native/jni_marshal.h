#ifndef PFV_JNI_MARSHAL_H
#define PFV_JNI_MARSHAL_H

#include <jni.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <omp.h>

#ifdef _WIN32
// For the conversion of paths (see nativePath), without windows.h's min and
// max macros or its rarely used headers
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
// (MinGW's headers define them only for some _WIN32_WINNT targets)
#ifndef WC_ERR_INVALID_CHARS
#define WC_ERR_INVALID_CHARS 0x00000080
#endif
#ifndef WC_NO_BEST_FIT_CHARS
#define WC_NO_BEST_FIT_CHARS 0x00000400
#endif
#endif

// Shared helpers for moving image data between JNI arrays and native buffers.
// Header-only on purpose: it must not include anything from cpp-tiff or
// cpp-zarr -- both install a helperfunctions.h with the same include guard, so
// each translation unit may only see one upstream's headers (see CMakeLists).

namespace pfv {

template <typename T> struct JniArrayTraits;

template <> struct JniArrayTraits<int8_t> {
	using ArrayType = jbyteArray;
	static constexpr const char* sliceClass = "[B";
	static ArrayType newArray(JNIEnv* env, jsize n) { return env->NewByteArray(n); }
};

template <> struct JniArrayTraits<int16_t> {
	using ArrayType = jshortArray;
	static constexpr const char* sliceClass = "[S";
	static ArrayType newArray(JNIEnv* env, jsize n) { return env->NewShortArray(n); }
};

template <> struct JniArrayTraits<float> {
	using ArrayType = jfloatArray;
	static constexpr const char* sliceClass = "[F";
	static ArrayType newArray(JNIEnv* env, jsize n) { return env->NewFloatArray(n); }
};

template <> struct JniArrayTraits<int32_t> {
	using ArrayType = jintArray;
	static constexpr const char* sliceClass = "[I";
	static ArrayType newArray(JNIEnv* env, jsize n) { return env->NewIntArray(n); }
};

// The UTF-8 of n UTF-16 units, surrogate pairs combined into one character
// (JNI's GetStringUTFChars gives Java's modified UTF-8 instead: each half of
// a pair on its own, which names no file). A lone surrogate becomes '?', as
// Java's own UTF-8 encoder writes it in the file names it makes.
inline std::string utf8Of(const jchar* s, size_t n) {
	std::string out;
	out.reserve(n);
	for (size_t i = 0; i < n; i++) {
		uint32_t c = s[i];
		if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && s[i + 1] >= 0xDC00 && s[i + 1] <= 0xDFFF)
			c = 0x10000 + ((c - 0xD800) << 10) + (uint32_t)(s[++i] - 0xDC00);
		else if (c >= 0xD800 && c <= 0xDFFF)
			c = '?';
		if (c < 0x80) {
			out += (char)c;
		}
		else if (c < 0x800) {
			out += (char)(0xC0 | (c >> 6));
			out += (char)(0x80 | (c & 0x3F));
		}
		else if (c < 0x10000) {
			out += (char)(0xE0 | (c >> 12));
			out += (char)(0x80 | ((c >> 6) & 0x3F));
			out += (char)(0x80 | (c & 0x3F));
		}
		else {
			out += (char)(0xF0 | (c >> 18));
			out += (char)(0x80 | ((c >> 12) & 0x3F));
			out += (char)(0x80 | ((c >> 6) & 0x3F));
			out += (char)(0x80 | (c & 0x3F));
		}
	}
	return out;
}

// The UTF-16 of the UTF-8 text s; a byte that does not start a valid UTF-8
// sequence becomes U+FFFD.
inline std::vector<jchar> utf16Of(const std::string& s) {
	std::vector<jchar> out;
	out.reserve(s.size());
	for (size_t i = 0; i < s.size();) {
		const unsigned char b = (unsigned char)s[i];
		uint32_t c;
		size_t len;
		if (b < 0x80) { c = b; len = 1; }
		else if (b >= 0xC2 && b <= 0xDF) { c = b & 0x1Fu; len = 2; }
		else if (b >= 0xE0 && b <= 0xEF) { c = b & 0x0Fu; len = 3; }
		else if (b >= 0xF0 && b <= 0xF4) { c = b & 0x07u; len = 4; }
		else { c = 0; len = 0; }
		bool ok = len > 0 && i + len <= s.size();
		for (size_t k = 1; ok && k < len; k++) {
			const unsigned char t = (unsigned char)s[i + k];
			ok = (t & 0xC0) == 0x80;
			c = (c << 6) | (t & 0x3Fu);
		}
		// (overlong forms, surrogates and values past U+10FFFF are not UTF-8)
		ok = ok && !(len == 3 && c < 0x800) && !(len == 4 && (c < 0x10000 || c > 0x10FFFF)) && !(c >= 0xD800 && c <= 0xDFFF);
		if (!ok) {
			out.push_back(0xFFFD);
			i++;
			continue;
		}
		if (c >= 0x10000) {
			out.push_back((jchar)(0xD800 + ((c - 0x10000) >> 10)));
			out.push_back((jchar)(0xDC00 + ((c - 0x10000) & 0x3FF)));
		}
		else {
			out.push_back((jchar)c);
		}
		i += len;
	}
	return out;
}

// Throw a java.lang.RuntimeException (no-op if an exception is already
// pending). msg is UTF-8, as the paths in it are: ThrowNew would read it as
// modified UTF-8, which differs for characters outside the BMP.
inline void throwRuntime(JNIEnv* env, const std::string& msg) {
	if (env->ExceptionCheck()) return;
	jclass cls = env->FindClass("java/lang/RuntimeException");
	if (cls == nullptr) return;
	const std::vector<jchar> text = utf16Of(msg);
	const jchar none = 0;
	jstring jmsg = env->NewString(text.empty() ? &none : text.data(), (jsize)text.size());
	jmethodID init = jmsg == nullptr ? nullptr : env->GetMethodID(cls, "<init>", "(Ljava/lang/String;)V");
	jobject e = init == nullptr ? nullptr : env->NewObject(cls, init, jmsg);
	// (when one of these fails, an OutOfMemoryError is pending instead)
	if (e != nullptr) {
		env->Throw((jthrowable)e);
		env->DeleteLocalRef(e);
	}
	if (jmsg != nullptr) env->DeleteLocalRef(jmsg);
	env->DeleteLocalRef(cls);
}

inline void throwRuntime(JNIEnv* env, const char* msg) {
	throwRuntime(env, std::string(msg != nullptr ? msg : ""));
}

// A path from Java as the JNI functions hand it to cpp-tiff, cpp-zarr and
// RawFile (native), and as it was given, in UTF-8 (utf8).
struct NativePath {
	std::string utf8, native;

	// msg with the path as the libraries had it put back as it was given
	// (cpp-zarr's messages hold the paths it was handed)
	std::string readable(const std::string& msg) const {
		if (native.empty() || native == utf8) return msg;
		std::string out;
		size_t from = 0;
		for (size_t at; (at = msg.find(native, from)) != std::string::npos; from = at + native.size())
			out.append(msg, from, at - from).append(utf8);
		return out.append(msg, from, std::string::npos);
	}
};

#ifdef _WIN32
namespace win {

// w in the ANSI code page, in which narrow Win32 calls (CreateFileA, fopen,
// mkdir) read their paths, if each of its characters is there as itself:
// WC_NO_BEST_FIT_CHARS keeps stand-ins that look alike (a plain a for an a
// with a ring) from naming another file, and the bytes are read back to be sure.
inline bool toAnsi(const std::wstring& w, std::string& out) {
	if (w.empty()) {
		out.clear();
		return true;
	}
	// With "Beta: Use Unicode UTF-8 for worldwide language support" the ANSI
	// code page is UTF-8, which takes neither that flag nor usedDefault
	const bool utf8 = GetACP() == CP_UTF8;
	const DWORD flags = utf8 ? WC_ERR_INVALID_CHARS : WC_NO_BEST_FIT_CHARS;
	BOOL usedDefault = FALSE;
	const int n = WideCharToMultiByte(CP_ACP, flags, w.data(), (int)w.size(), nullptr, 0, nullptr, utf8 ? nullptr : &usedDefault);
	if (n <= 0 || usedDefault) return false;
	std::string a((size_t)n, '\0');
	if (WideCharToMultiByte(CP_ACP, flags, w.data(), (int)w.size(), &a[0], n, nullptr, utf8 ? nullptr : &usedDefault) != n || usedDefault)
		return false;
	const int m = MultiByteToWideChar(CP_ACP, MB_ERR_INVALID_CHARS, a.data(), n, nullptr, 0);
	if (m != (int)w.size()) return false;
	std::wstring back((size_t)m, L'\0');
	if (MultiByteToWideChar(CP_ACP, MB_ERR_INVALID_CHARS, a.data(), n, &back[0], m) != m || back != w) return false;
	out.swap(a);
	return true;
}

// The 8.3 short form of the path w of a file or folder that exists. Where
// the volume keeps no short name for a component, Windows gives that
// component as it is.
inline bool shortPath(const std::wstring& w, std::wstring& out) {
	const DWORD n = GetShortPathNameW(w.c_str(), nullptr, 0);
	if (n == 0) return false;
	std::wstring s((size_t)n, L'\0');
	const DWORD got = GetShortPathNameW(w.c_str(), &s[0], n);
	if (got == 0 || got >= n) return false;
	s.resize(got);
	out.swap(s);
	return true;
}

// The short form of w (see shortPath), or for a file not made yet (a save),
// the short form of the deepest folder of it that exists followed by the rest
// of w as it is.
inline bool shortPathOf(const std::wstring& w, std::wstring& out) {
	if (shortPath(w, out)) return true;
	for (size_t end = w.find_last_of(L"\\/"); end != std::wstring::npos && end > 0; end = w.find_last_of(L"\\/", end - 1)) {
		std::wstring parent = w.substr(0, end), s;
		if (parent.back() == L':') parent += L'\\'; // a drive's root
		if (!shortPath(parent, s)) continue;
		if (s.back() != L'\\' && s.back() != L'/') s += L'\\';
		out = s + w.substr(end + 1);
		return true;
	}
	return false;
}

} // namespace win
#endif

// The path as the libraries are to open it: false, with a RuntimeException
// pending, if it cannot be passed to them. Every JNI function that takes a
// path converts it here, RawFile's reads included, so all open the same file.
//
// It is UTF-8 (standard, not JNI's modified UTF-8) everywhere but on Windows.
// There cpp-tiff and cpp-zarr 1.7.3 open files with narrow calls (TIFFOpen's
// CreateFileA, fopen, std::ifstream of a std::string), which read the path in
// the ANSI code page, as MATLAB passes it: an ASCII path goes as it is; else
// the path in the ANSI code page, where it has all of its characters; else
// its 8.3 short form (ASCII where the volume keeps short names), for a file
// not made yet that of its deepest existing folder followed by the rest, if
// that is in the ANSI code page. A path none of these can name is refused,
// with a message saying what to do.
// TODO: remove the Windows conversion when PFV moves to cpp-tiff and cpp-zarr
// releases that accept UTF-8 paths on Windows, and pass UTF-8 there too.
inline bool nativePath(JNIEnv* env, jstring path, NativePath& out) {
	if (path == nullptr) {
		throwRuntime(env, "No file name was given");
		return false;
	}
	const jsize n = env->GetStringLength(path);
	std::vector<jchar> u((size_t)n + 1, 0);
	if (n > 0) env->GetStringRegion(path, 0, n, u.data());
	if (env->ExceptionCheck()) return false;
	out.utf8 = utf8Of(u.data(), (size_t)n);
	bool ascii = true;
	for (jsize i = 0; i < n; i++) {
		if (u[(size_t)i] == 0) {
			// C would take the path to end there, at another file
			throwRuntime(env, "The path \"" + out.utf8 + "\" holds a NUL character, which no file name can hold");
			return false;
		}
		if (u[(size_t)i] >= 0x80) ascii = false;
	}
#ifdef _WIN32
	if (ascii) {
		out.native = out.utf8;
		return true;
	}
	const std::wstring w(u.begin(), u.begin() + n);
	std::wstring s;
	if (win::toAnsi(w, out.native)) return true;
	if (win::shortPathOf(w, s) && win::toAnsi(s, out.native)) return true;
	out.native.clear();
	throwRuntime(env, out.utf8 + ": the name of a folder or file in this path has characters that Windows cannot pass to "
		"the TIFF and Zarr readers and writers in this version of the plugin. Rename the folder or file, or move the file "
		"to a folder whose path has none of them.");
	return false;
#else
	(void)ascii;
	out.native = out.utf8;
	return true;
#endif
}

// Called from JNI_OnLoad of each JNI library. cpp-tiff and cpp-zarr each link
// a private copy of the C++ runtime (-static-libstdc++), and on macOS and
// Windows of GCC's unwinder too (-static-libgcc); our libraries take their C++
// runtime from libcppTiff's exported copy. A cpp-zarr exception could
// therefore be unwound by cpp-zarr's unwinder but handled in our frames by
// cpp-tiff's personality routine, whose unwinder helpers (GCC < 14) fill their
// register-size table only the first time their own unwinder runs; until then
// they abort the process. Throwing and catching once here fills that table.
// Since cpp-tiff and cpp-zarr v1.7.3, every library here unwinds with the
// system's shared libgcc_s on Linux, where this is no longer needed.
inline void primeUnwinder() {
	try {
		throw 0;
	}
	catch (int) {
	}
}

// cpp-zarr throws std::string codes such as "metadataFileMissing:<path>" or
// "metadataUnsupported:<reason>"; turn them into messages a user can act on.
inline std::string zarrError(const std::string& code) {
	const size_t colon = code.find(':');
	const std::string kind = code.substr(0, colon);
	const std::string detail = colon == std::string::npos ? "" : code.substr(colon + 1);
	if (kind == "metadataFileMissing" || kind == "cannotOpenZarray")
		return "No Zarr metadata found" + (detail.empty() ? std::string() : " (" + detail + ")") + "; is this a Zarr array?";
	if (kind == "metadataIncomplete")
		return "The Zarr metadata is incomplete or invalid" + (detail.empty() ? std::string() : ": " + detail);
	if (kind == "metadataUnsupported" || kind == "zarrFormatUnsupported")
		return "This Zarr array is not supported" + (detail.empty() ? std::string() : ": " + detail);
	if (kind == "unsupportedCompressor")
		return "This Zarr array uses a compressor that is not supported";
	return "Zarr error: " + code;
}

// IEEE 754 half precision to float. Exact: every half value is a float.
inline float halfToFloat(uint16_t h) {
	const uint32_t sign = (uint32_t)(h & 0x8000u) << 16;
	uint32_t exponent = (h >> 10) & 0x1Fu;
	uint32_t mantissa = h & 0x3FFu;
	uint32_t bits;
	if (exponent == 0x1Fu) {
		bits = sign | 0x7F800000u | (mantissa << 13); // infinity or NaN
	}
	else if (exponent != 0) {
		bits = sign | ((exponent + 112) << 23) | (mantissa << 13); // rebias 15 -> 127
	}
	else if (mantissa == 0) {
		bits = sign; // +-0
	}
	else {
		exponent = 113; // subnormal: shift the leading 1 into the implicit bit
		while (!(mantissa & 0x400u)) {
			mantissa <<= 1;
			exponent--;
		}
		bits = sign | (exponent << 23) | ((mantissa & 0x3FFu) << 13);
	}
	float f;
	memcpy(&f, &bits, sizeof f);
	return f;
}

// bfloat16 is the top half of a float.
inline float bfloat16ToFloat(uint16_t b) {
	const uint32_t bits = (uint32_t)b << 16;
	float f;
	memcpy(&f, &bits, sizeof f);
	return f;
}

// Caps the threads of the OpenMP regions the calling thread starts while it
// lives: a read of a small file with every core spends longer starting the
// threads (each opening the file) than reading. It should cover a whole JNI
// call, so that call's regions keep one team size: libgomp ends threads when
// a region uses fewer than the last and starts new ones when it uses more.
class ThreadCap {
	int previous;
public:
	explicit ThreadCap(uint64_t threads) : previous(omp_get_max_threads()) {
		if (threads < (uint64_t)previous) omp_set_num_threads(threads > 0 ? (int)threads : 1);
	}
	~ThreadCap() { omp_set_num_threads(previous); }
	ThreadCap(const ThreadCap&) = delete;
	ThreadCap& operator=(const ThreadCap&) = delete;
};

// Copy n equally sized planes with all cores in ONE parallel region. Forking
// a region costs a thread wake-up (milliseconds once the OpenMP threads have
// gone to sleep), which dwarfed the copy of a 2 MB plane when done per plane:
// a 10000-slice stack spent ~30 s in wake-ups. Callers therefore batch planes.
// One core here copies at ~1.7 GB/s while 32 reach ~15 GB/s; tiny batches
// stay serial.
inline void copyPlanes(void* const* dst, const void* const* src, int64_t n, uint64_t bytes) {
	if (n <= 0 || bytes == 0) return;
	if ((uint64_t)n * bytes <= (1u << 20)) {
		for (int64_t k = 0; k < n; k++) memcpy(dst[k], src[k], (size_t)bytes);
		return;
	}
	const uint64_t chunk = 256u << 10;
	const int64_t chunksPerPlane = (int64_t)((bytes + chunk - 1) / chunk);
	const int64_t total = n * chunksPerPlane;
	#pragma omp parallel for schedule(static)
	for (int64_t c = 0; c < total; c++) {
		const int64_t k = c / chunksPerPlane;
		const uint64_t a = (uint64_t)(c % chunksPerPlane) * chunk;
		const uint64_t b = a + chunk < bytes ? a + chunk : bytes;
		memcpy((char*)dst[k] + a, (const char*)src[k] + a, (size_t)(b - a));
	}
}

// The most planes a batch pins: each holds a JNI local reference meanwhile
const uint64_t maxBatchPlanes = 4096;

// Planes per batch: about 256 MB, at least one plane, at most maxBatchPlanes.
inline uint64_t planesPerBatch(uint64_t sliceBytes) {
	const uint64_t target = 256u << 20;
	uint64_t n = sliceBytes == 0 ? maxBatchPlanes : target / sliceBytes;
	if (n < 1) n = 1;
	if (n > maxBatchPlanes) n = maxBatchPlanes;
	return n;
}

// Build a T[nSlices][sliceElems] Java array, filled by fill(dst, first,
// count), which must write planes [first, first + count) to dst[0..count).
// Slices are allocated a batch at a time, pinned with GetPrimitiveArrayCritical
// (nesting is allowed), filled by one call (one parallel region) and released;
// nothing but the critical get/release and fill runs while a batch is pinned.
// Set*ArrayRegion was single-threaded and could not take more than INT_MAX/2
// elements per call. With dest (a T[nSlices][sliceElems] allocated by the
// caller, e.g. from all cores at once) the planes are filled in place; a dest
// that does not match the image is rejected with an exception rather than
// written past.
template <typename T, typename Fill>
jobjectArray slicesToJavaWith(JNIEnv* env, uint64_t sliceElems, uint64_t nSlices, jobjectArray dest, Fill fill) {
	using Traits = JniArrayTraits<T>;
	jobjectArray outer = dest;
	if (dest == nullptr) {
		jclass sliceCls = env->FindClass(Traits::sliceClass);
		if (sliceCls == nullptr) return nullptr;
		outer = env->NewObjectArray((jsize)nSlices, sliceCls, nullptr);
		env->DeleteLocalRef(sliceCls);
		if (outer == nullptr) return nullptr;
	}
	else if ((uint64_t)env->GetArrayLength(dest) != nSlices) {
		throwRuntime(env, "Destination planes do not match the image slice count");
		return nullptr;
	}
	const uint64_t sliceBytes = sliceElems * sizeof(T);
	const uint64_t perBatch = planesPerBatch(sliceBytes);
	std::vector<typename Traits::ArrayType> arrays;
	std::vector<T*> dst;
	for (uint64_t start = 0; start < nSlices; start += perBatch) {
		const uint64_t end = start + perBatch < nSlices ? start + perBatch : nSlices;
		arrays.clear(); dst.clear();
		if (env->EnsureLocalCapacity((jint)(end - start) + 4) < 0) return nullptr;
		for (uint64_t i = start; i < end; i++) {
			typename Traits::ArrayType slice;
			if (dest == nullptr) {
				slice = Traits::newArray(env, (jsize)sliceElems);
				if (slice == nullptr) { // OutOfMemoryError already pending
					for (auto a : arrays) env->DeleteLocalRef(a);
					return nullptr;
				}
				env->SetObjectArrayElement(outer, (jsize)i, slice);
			}
			else {
				slice = (typename Traits::ArrayType)env->GetObjectArrayElement(dest, (jsize)i);
				if (slice == nullptr || (uint64_t)env->GetArrayLength(slice) != sliceElems) {
					if (slice != nullptr) env->DeleteLocalRef(slice);
					for (auto a : arrays) env->DeleteLocalRef(a);
					throwRuntime(env, "Destination plane has the wrong size for the image");
					return nullptr;
				}
			}
			arrays.push_back(slice);
		}
		for (size_t k = 0; k < arrays.size(); k++) {
			void* p = env->GetPrimitiveArrayCritical(arrays[k], nullptr);
			if (p == nullptr) {
				for (size_t j = 0; j < dst.size(); j++) env->ReleasePrimitiveArrayCritical(arrays[j], dst[j], 0);
				for (auto a : arrays) env->DeleteLocalRef(a);
				return nullptr;
			}
			dst.push_back((T*)p);
		}
		fill(dst.data(), start, (uint64_t)dst.size());
		for (size_t k = 0; k < dst.size(); k++) env->ReleasePrimitiveArrayCritical(arrays[k], dst[k], 0);
		for (auto a : arrays) env->DeleteLocalRef(a);
	}
	return outer;
}

// Fill the existing Java planes dest[idx[k]], k in [0, n), each of sliceElems
// elements, by fill(dst, k0, count), which must write planes idx[k0..k0+count)
// to dst[0..count). Pinned a batch at a time like slicesToJavaWith, the batch
// from k0 holding batch(k0) planes (at least one, at most maxBatchPlanes; the
// overload below pins about 256 MB at a time). Returns false, with a Java
// exception pending, if a plane is missing or the wrong size.
template <typename T, typename Fill, typename Batch>
bool fillJavaPlanes(JNIEnv* env, jobjectArray dest, uint64_t sliceElems, const uint64_t* idx, uint64_t n, Fill fill, Batch batch) {
	using Traits = JniArrayTraits<T>;
	std::vector<typename Traits::ArrayType> arrays;
	std::vector<T*> dst;
	for (uint64_t k0 = 0, k1; k0 < n; k0 = k1) {
		uint64_t perBatch = batch(k0);
		if (perBatch < 1) perBatch = 1;
		if (perBatch > maxBatchPlanes) perBatch = maxBatchPlanes;
		k1 = k0 + perBatch < n ? k0 + perBatch : n;
		arrays.clear(); dst.clear();
		if (env->EnsureLocalCapacity((jint)(k1 - k0) + 4) < 0) return false;
		for (uint64_t k = k0; k < k1; k++) {
			typename Traits::ArrayType slice = (typename Traits::ArrayType)env->GetObjectArrayElement(dest, (jsize)idx[k]);
			if (slice == nullptr || (uint64_t)env->GetArrayLength(slice) != sliceElems) {
				if (slice != nullptr) env->DeleteLocalRef(slice);
				for (auto a : arrays) env->DeleteLocalRef(a);
				throwRuntime(env, "Destination plane has the wrong size for the image");
				return false;
			}
			arrays.push_back(slice);
		}
		for (size_t j = 0; j < arrays.size(); j++) {
			void* p = env->GetPrimitiveArrayCritical(arrays[j], nullptr);
			if (p == nullptr) {
				for (size_t i = 0; i < dst.size(); i++) env->ReleasePrimitiveArrayCritical(arrays[i], dst[i], 0);
				for (auto a : arrays) env->DeleteLocalRef(a);
				return false;
			}
			dst.push_back((T*)p);
		}
		fill(dst.data(), k0, (uint64_t)dst.size());
		for (size_t j = 0; j < dst.size(); j++) env->ReleasePrimitiveArrayCritical(arrays[j], dst[j], 0);
		for (auto a : arrays) env->DeleteLocalRef(a);
	}
	return true;
}

template <typename T, typename Fill>
bool fillJavaPlanes(JNIEnv* env, jobjectArray dest, uint64_t sliceElems, const uint64_t* idx, uint64_t n, Fill fill) {
	const uint64_t perBatch = planesPerBatch(sliceElems * sizeof(T));
	return fillJavaPlanes<T>(env, dest, sliceElems, idx, n, fill, [perBatch](uint64_t) { return perBatch; });
}

// Build a T[nSlices][sliceElems] Java array from a native buffer holding the
// slices one after another, or with offsets, slice i at data + offsets[i]
// (so planes stored in another order need no reordering copy).
template <typename T>
jobjectArray slicesToJava(JNIEnv* env, const T* data, uint64_t sliceElems, uint64_t nSlices,
                          jobjectArray dest = nullptr, const uint64_t* offsets = nullptr) {
	const uint64_t sliceBytes = sliceElems * sizeof(T);
	std::vector<const void*> src;
	return slicesToJavaWith<T>(env, sliceElems, nSlices, dest, [&](T* const* dst, uint64_t first, uint64_t count) {
		src.clear();
		for (uint64_t k = 0; k < count; k++)
			src.push_back(data + (offsets != nullptr ? offsets[first + k] : (first + k) * sliceElems));
		copyPlanes((void* const*)dst, src.data(), (int64_t)count, sliceBytes);
	});
}

// Where planes lie in a native buffer: pixel (x, y) of plane p is element
// base[p] + x*sx + y*sy (x across a row of w pixels, y down h rows). They fill
// the rectangle of w x h pixels at (x0, y0) of planes whose rows are rowLength
// pixels long, or whole planes of w x h pixels when rowLength is 0 (as when
// the last three are left out): a Zarr read in bricks fills a part of each
// plane at a time.
struct PlaneLayout {
	uint64_t w, h, sx, sy;
	const uint64_t* base;
	uint64_t x0, y0, rowLength;
	uint64_t row() const { return rowLength != 0 ? rowLength : w; }
	// Where the rectangle starts in its planes
	uint64_t offset() const { return y0 * row() + x0; }
	// Whether the rectangle is one block in the buffer and in its planes
	bool contiguous() const { return (w == 1 || sx == 1) && (h == 1 || (sy == w && row() == w)); }
};

// Conversions of each element on its way into Java's arrays
template <typename S, typename D>
struct Cast {
	D operator()(S v) const { return (D)v; }
};

// Signed integers, XOR-shifted into the unsigned range ImageJ expects: +128
// for int8 (mask 0x80) and +32768 for int16 (mask 0x8000) are bitwise
// identical to flipping the sign bit. A Java-side calibration maps back.
template <typename S, typename D>
struct XorSign {
	S mask;
	D operator()(S v) const { return (D)(S)(v ^ mask); }
};

struct HalfToFloat {
	bool bfloat16;
	float operator()(uint16_t v) const { return bfloat16 ? bfloat16ToFloat(v) : halfToFloat(v); }
};

// Whether conv leaves elements as they are, so planes can be memcpy'd
template <typename Conv, typename S, typename D> struct PlainCopy { static const bool value = false; };
template <typename T> struct PlainCopy<Cast<T, T>, T, T> { static const bool value = true; };

// Fill dst[0..count) with planes [first, first + count) of src laid out as L,
// converting every element with conv: straight into Java's (pinned) arrays,
// with no intermediate copy of the image. Contiguous planes go in blocks;
// rectangles whose rows are contiguous in src row by row; the others in 64x64
// tiles, which keep the reads of transposed planes (MATLAB's (y, x) layout,
// sx = h) in cache. Fills of up to 1 MB stay on one thread (see copyPlanes).
template <typename S, typename D, typename Conv>
void fillPlanes(D* const* dst, uint64_t first, uint64_t count, const S* src, const PlaneLayout& L, Conv conv) {
	const uint64_t n = L.w * L.h, row = L.row(), at = L.offset();
	const bool parallel = count * n * sizeof(D) > (1u << 20);
	const bool plain = PlainCopy<Conv, S, D>::value;
	if (L.contiguous() && plain) {
		// memcpy outruns an element loop (by up to 5% on some CPUs)
		std::vector<const void*> from(count);
		std::vector<void*> to(count);
		for (uint64_t p = 0; p < count; p++) {
			from[p] = src + L.base[first + p];
			to[p] = dst[p] + at;
		}
		copyPlanes(to.data(), from.data(), (int64_t)count, n * sizeof(D));
		return;
	}
	if (L.contiguous()) {
		const uint64_t block = 1u << 16, per = (n + block - 1) / block;
		const int64_t total = (int64_t)(count * per);
		#pragma omp parallel for schedule(static) if(parallel)
		for (int64_t k = 0; k < total; k++) {
			const uint64_t p = (uint64_t)k / per, i0 = ((uint64_t)k % per) * block, i1 = i0 + block < n ? i0 + block : n;
			const S* s = src + L.base[first + p];
			D* d = dst[p] + at;
			for (uint64_t i = i0; i < i1; i++) d[i] = conv(s[i]);
		}
		return;
	}
	const uint64_t B = 64, rowTiles = (L.h + B - 1) / B;
	const int64_t total = (int64_t)(count * rowTiles);
	if (L.w == 1 || L.sx == 1) {
		#pragma omp parallel for schedule(static) if(parallel)
		for (int64_t k = 0; k < total; k++) {
			const uint64_t p = (uint64_t)k / rowTiles, y0 = ((uint64_t)k % rowTiles) * B, y1 = y0 + B < L.h ? y0 + B : L.h;
			const S* s = src + L.base[first + p];
			D* d = dst[p] + at;
			for (uint64_t y = y0; y < y1; y++) {
				D* r = d + y * row;
				const S* sr = s + y * L.sy;
				if (plain) memcpy(r, sr, (size_t)(L.w * sizeof(D)));
				else for (uint64_t x = 0; x < L.w; x++) r[x] = conv(sr[x]);
			}
		}
		return;
	}
	#pragma omp parallel for schedule(static) if(parallel)
	for (int64_t k = 0; k < total; k++) {
		const uint64_t p = (uint64_t)k / rowTiles, y0 = ((uint64_t)k % rowTiles) * B, y1 = y0 + B < L.h ? y0 + B : L.h;
		const S* s = src + L.base[first + p];
		D* d = dst[p] + at;
		for (uint64_t x0 = 0; x0 < L.w; x0 += B) {
			const uint64_t x1 = x0 + B < L.w ? x0 + B : L.w;
			for (uint64_t y = y0; y < y1; y++) {
				D* r = d + y * row;
				const S* sr = s + y * L.sy;
				for (uint64_t x = x0; x < x1; x++) r[x] = conv(sr[x * L.sx]);
			}
		}
	}
}

// Whether slices holds nSlices primitive arrays of sliceElems elements of
// elemBytes bytes each, which the copy below reads without looking; if not,
// a RuntimeException saying what is wrong is pending. (A virtual stack has no
// array of planes: handed one as null, the JVM would crash.)
inline bool checkSlices(JNIEnv* env, jobjectArray slices, uint64_t sliceElems,
                        uint64_t nSlices, uint64_t elemBytes) {
	if (slices == nullptr) {
		throwRuntime(env, "There are no planes to write (the image has none in memory)");
		return false;
	}
	const uint64_t given = (uint64_t)env->GetArrayLength(slices);
	if (given < nSlices) {
		throwRuntime(env, ("Expected " + std::to_string(nSlices) + " planes to write, got " + std::to_string(given)).c_str());
		return false;
	}
	// The primitive arrays whose elements have elemBytes bytes
	const char* names[2] = { nullptr, nullptr };
	if (elemBytes == 1) names[0] = "[B";
	else if (elemBytes == 2) { names[0] = "[S"; names[1] = "[C"; }
	else if (elemBytes == 4) { names[0] = "[F"; names[1] = "[I"; }
	else if (elemBytes == 8) { names[0] = "[D"; names[1] = "[J"; }
	jclass types[2] = { nullptr, nullptr };
	for (int k = 0; k < 2; k++)
		if (names[k] != nullptr && (types[k] = env->FindClass(names[k])) == nullptr) return false;
	bool ok = true;
	for (uint64_t i = 0; i < nSlices && ok; i++) {
		jobject slice = env->GetObjectArrayElement(slices, (jsize)i);
		if (slice == nullptr) {
			throwRuntime(env, ("Plane " + std::to_string(i + 1) + " to write is missing").c_str());
			ok = false;
			break;
		}
		const bool typed = (types[0] != nullptr && env->IsInstanceOf(slice, types[0])) ||
		                   (types[1] != nullptr && env->IsInstanceOf(slice, types[1]));
		if (!typed || (uint64_t)env->GetArrayLength((jarray)slice) != sliceElems) {
			throwRuntime(env, ("Plane " + std::to_string(i + 1) + " to write is not " + std::to_string(sliceElems) +
				" values of " + std::to_string(8 * elemBytes) + " bits").c_str());
			ok = false;
		}
		env->DeleteLocalRef(slice);
	}
	for (jclass t : types)
		if (t != nullptr) env->DeleteLocalRef(t);
	return ok;
}

// Copy the first nSlices planes of slices, each sliceBytes bytes, into buf:
// plane k to the plane-sized slot first + k * stride of buf. Batched and
// pinned like slicesToJava; the planes must have been checked (checkSlices).
// Returns false if a batch could not be pinned.
inline bool copySlicesTo(JNIEnv* env, jobjectArray slices, uint64_t sliceBytes, uint64_t nSlices,
                         void* buf, uint64_t first, uint64_t stride) {
	const uint64_t perBatch = planesPerBatch(sliceBytes);
	std::vector<jarray> arrays;
	std::vector<const void*> src;
	std::vector<void*> dst;
	for (uint64_t start = 0; start < nSlices; start += perBatch) {
		const uint64_t end = start + perBatch < nSlices ? start + perBatch : nSlices;
		arrays.clear(); src.clear(); dst.clear();
		if (env->EnsureLocalCapacity((jint)(end - start) + 4) < 0) return false;
		for (uint64_t i = start; i < end; i++) {
			jarray slice = (jarray)env->GetObjectArrayElement(slices, (jsize)i);
			if (slice == nullptr) {
				for (auto a : arrays) env->DeleteLocalRef(a);
				return false;
			}
			arrays.push_back(slice);
		}
		for (size_t k = 0; k < arrays.size(); k++) {
			void* p = env->GetPrimitiveArrayCritical(arrays[k], nullptr);
			if (p == nullptr) {
				for (size_t j = 0; j < src.size(); j++) env->ReleasePrimitiveArrayCritical(arrays[j], (void*)src[j], JNI_ABORT);
				for (auto a : arrays) env->DeleteLocalRef(a);
				return false;
			}
			src.push_back(p);
			dst.push_back((char*)buf + (first + (start + k) * stride) * sliceBytes);
		}
		copyPlanes(dst.data(), src.data(), (int64_t)src.size(), sliceBytes);
		for (size_t k = 0; k < src.size(); k++) env->ReleasePrimitiveArrayCritical(arrays[k], (void*)src[k], JNI_ABORT);
		for (auto a : arrays) env->DeleteLocalRef(a);
	}
	return true;
}

// Copy a Java T[nSlices][sliceElems] into one contiguous native buffer
// (malloc'd; the caller frees). elemBytes is the element size (1, 2 or 4);
// the copy itself is type-agnostic. Batched and pinned like slicesToJava.
// Planes that are missing or of another size or type are refused (see
// checkSlices): null is returned with a RuntimeException pending.
inline void* slicesToNative(JNIEnv* env, jobjectArray slices, uint64_t sliceElems,
                            uint64_t nSlices, uint64_t elemBytes) {
	if (!checkSlices(env, slices, sliceElems, nSlices, elemBytes)) return nullptr;
	const uint64_t sliceBytes = sliceElems * elemBytes;
	void* buf = malloc(sliceBytes * nSlices);
	if (buf == nullptr) return nullptr;
	if (!copySlicesTo(env, slices, sliceBytes, nSlices, buf, 0, 1)) {
		free(buf);
		return nullptr;
	}
	return buf;
}

// Copy the first nSlices planes of slices into buf, a native buffer of
// bufBytes bytes, plane k to the plane-sized slot first + k * stride (a run
// of a save holds the planes of its channels interleaved). Planes that are
// missing or of another size or type are refused as by slicesToNative, and so
// are slots past the end of buf: false is returned with a RuntimeException
// pending.
inline bool slicesIntoNative(JNIEnv* env, jobjectArray slices, uint64_t sliceElems, uint64_t nSlices,
                             uint64_t elemBytes, void* buf, uint64_t bufBytes, uint64_t first, uint64_t stride) {
	if (!checkSlices(env, slices, sliceElems, nSlices, elemBytes)) return false;
	const uint64_t sliceBytes = sliceElems * elemBytes;
	if (nSlices == 0) return true;
	// (the last slot first + (nSlices - 1) * stride, tested without overflowing)
	const uint64_t slots = sliceBytes == 0 ? 0 : bufBytes / sliceBytes;
	if (buf == nullptr || first >= slots || (nSlices > 1 && stride > (slots - 1 - first) / (nSlices - 1))) {
		throwRuntime(env, "The planes to write do not fit their native buffer");
		return false;
	}
	if (!copySlicesTo(env, slices, sliceBytes, nSlices, buf, first, stride)) {
		throwRuntime(env, "Could not reach the planes to write");
		return false;
	}
	return true;
}

} // namespace pfv

#endif
