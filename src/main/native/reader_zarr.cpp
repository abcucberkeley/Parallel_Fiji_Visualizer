// Zarr half of libParallelReadC: JNI bindings for edu.abc.berkeley.ParallelReadNative.
// Delegates to cpp-zarr; this translation unit must only see cpp-zarr headers
// (see CMakeLists.txt -- helperfunctions.h clashes between the upstreams).
//
// cpp-zarr reports problems by throwing std::string. Nothing may escape a JNI
// function: an uncaught C++ exception aborts the JVM, taking Fiji with it. So
// every call into cpp-zarr goes through readToJava() or withZarr() below.
//
// A read takes a window [start, end) with one value per axis of the array,
// and which axes are the image's x, y, z, channel and time ({x, y, z, c, t};
// x and y are required, the others are -1 when absent). Every other axis must
// have extent 1 in the window. The result is ImageJ planes in its czt order:
// plane c + C*(z + Z*t) holds the rows y, each holding the x values in order.

#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

#include "helperfunctions.h" // cpp-zarr's
#include "parallelreadzarr.h"
#include "zarr.h"

#include "jni_marshal.h"
#include "edu_abc_berkeley_ParallelReadNative.h"

namespace {

std::vector<uint64_t> toVector(JNIEnv* env, jlongArray a) {
	const jsize n = a == nullptr ? 0 : env->GetArrayLength(a);
	std::vector<jlong> tmp((size_t)n);
	if (n > 0) env->GetLongArrayRegion(a, 0, n, tmp.data());
	return std::vector<uint64_t>(tmp.begin(), tmp.end());
}

enum { X, Y, Z, C, T };

// With the environment variable PFV_TIMING set, every read prints the seconds
// of its parts on stderr (for measurements; the plugin itself never sets it)
bool timingWanted() {
	static const bool on = getenv("PFV_TIMING") != nullptr;
	return on;
}

double now() {
	return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

// A read's window [start, end), which of the array's axes are x, y, z, c and t
// (and the image's size along them), the memory order cpp-zarr writes the
// window in, and the pieces it is read in: along each image axis i the
// window-relative ranges [cuts[i][k], cuts[i][k + 1]), every combination of
// one range per axis read by one cpp-zarr call. byIndex marks the axes read
// one index at a time because their indices interleave the planes.
struct Window {
	std::vector<uint64_t> start, end;
	jint axes[5];
	uint64_t size[5];
	bool cOrder = false;
	bool byIndex[5] = { false, false, false, false, false };
	std::vector<uint64_t> cuts[5];
	uint64_t planes() const { return size[Z] * size[C] * size[T]; }
	// The most indices a piece holds along image axis i
	uint64_t thickest(int i) const {
		uint64_t most = 0;
		for (size_t k = 0; k + 1 < cuts[i].size(); k++) most = std::max(most, cuts[i][k + 1] - cuts[i][k]);
		return most;
	}
	uint64_t pieces(int i) const { return cuts[i].size() - 1; }
	uint64_t reads() const { return pieces(X) * pieces(Y) * pieces(Z) * pieces(C) * pieces(T); }
	// z, c and t from the slowest to the fastest in the read's memory order
	// (axes the image does not have last)
	std::vector<int> nesting() const {
		std::vector<int> order = { Z, C, T };
		const jint* a = axes;
		const bool c = cOrder;
		std::stable_sort(order.begin(), order.end(), [a, c](int p, int q) {
			if ((a[p] < 0) != (a[q] < 0)) return a[q] < 0;
			return c ? a[p] < a[q] : a[p] > a[q];
		});
		return order;
	}
	// The axes as the reads are nested, the slowest first: z, c and t as
	// nesting() has them, then the plane's own axes, the slower of y and x in
	// the read's memory order first
	std::vector<int> order() const {
		std::vector<int> all = nesting();
		const bool yFirst = cOrder ? axes[Y] < axes[X] : axes[Y] > axes[X];
		all.push_back(yFirst ? Y : X);
		all.push_back(yFirst ? X : Y);
		return all;
	}
};

// Slabs hold at least this much: per-read costs stay small next to the read.
// (Tests set it lower to read small arrays in slabs.)
uint64_t minSlabBytes = (uint64_t)1 << 30;

// How a read is cut, from the slowest axis: "t in 2, z in 4, c by index", or
// "" when it is read at once
std::string cutsOf(const Window& w) {
	static const char* const names[5] = { "x", "y", "z", "c", "t" };
	std::string s;
	for (int i : w.order()) {
		if (w.pieces(i) < 2) continue;
		s += std::string(s.empty() ? "" : ", ") + names[i] + (w.byIndex[i] ? " by index" : " in " + std::to_string(w.pieces(i)));
	}
	return s;
}

// The same with the read's memory order and number of reads, for tests: "C
// order, 16 reads: t in 2, z in 4, c by index" or "F order, 1 read"
std::string describe(const Window& w) {
	const uint64_t reads = w.reads();
	const std::string cuts = cutsOf(w);
	return std::string(w.cOrder ? "C" : "F") + " order, " + std::to_string(reads) + (reads == 1 ? " read" : " reads")
		+ (cuts.empty() ? "" : ": " + cuts);
}

// Checks a read and plans it; returns why it cannot be done, or "".
std::string plan(zarr& Zarr, const std::vector<uint64_t>& start, const std::vector<uint64_t>& end,
                 const jint* axes, uint64_t elemBytes, Window& w)
{
	const int64_t n = (int64_t)Zarr.get_ndims();
	if ((int64_t)start.size() != n || (int64_t)end.size() != n)
		return "The read window has " + std::to_string(start.size()) + " axes but the Zarr array has " + std::to_string(n);
	if (Zarr.dtypeBytes() != elemBytes)
		return "The Zarr array's data type (" + Zarr.get_dtype() + ") does not match the read";
	const std::string compressor = Zarr.get_compressor();
	if (compressor != "none" && compressor != "blosc" && compressor != "gzip" && compressor != "zstd")
		return "This Zarr array uses the \"" + compressor + "\" compressor, which is not supported";
	for (int i = 0; i < 5; i++) {
		if ((i < 2 && axes[i] < 0) || axes[i] < -1 || axes[i] >= n) return "Invalid axes for the Zarr array";
		for (int j = 0; j < i; j++)
			if (axes[i] >= 0 && axes[i] == axes[j]) return "Invalid axes for the Zarr array";
	}
	std::vector<uint64_t> extent((size_t)n);
	for (int64_t d = 0; d < n; d++) {
		if (end[d] < start[d] || end[d] > Zarr.get_shape(d)) return "The read window lies outside the Zarr array";
		extent[d] = end[d] - start[d];
		bool role = false;
		for (int i = 0; i < 5; i++) role = role || axes[i] == d;
		if (!role && extent[d] != 1) return "The read window spans an axis that is not x, y, z, c or t";
	}
	w.start = start;
	w.end = end;
	for (int i = 0; i < 5; i++) {
		w.axes[i] = axes[i];
		w.size[i] = axes[i] >= 0 ? extent[axes[i]] : 1;
	}

	// Read in the memory order that keeps each plane one compact block: F when
	// x and y come before the other axes read (MATLAB's y, x, z, c, t), C when
	// they come after them (NumPy's t, c, z, y, x); otherwise the array's own
	// order, which cpp-zarr copies chunks into fastest
	const int64_t lo = axes[X] < axes[Y] ? axes[X] : axes[Y];
	const int64_t hi = axes[X] < axes[Y] ? axes[Y] : axes[X];
	bool first = true, last = true;
	for (int64_t d = 0; d < n; d++) {
		if (d == axes[X] || d == axes[Y] || extent[d] == 1) continue;
		if (d < hi) first = false;
		if (d > lo) last = false;
	}
	w.cOrder = last && !first ? true : first && !last ? false : Zarr.get_order() == "C";
	for (int i = 0; i < 5; i++) w.cuts[i] = { 0, w.size[i] };

	uint64_t bytes = w.size[X] * w.size[Y] * w.planes() * elemBytes;

	// The indices along array axis d that one decoded chunk holds: for a
	// sharded array its inner chunks, which are decoded one by one
	auto chunkExtent = [&Zarr](int64_t d) -> uint64_t {
		return Zarr.get_shard() ? Zarr.get_chunk_shape((uint64_t)d) : Zarr.get_chunks((uint64_t)d);
	};

	// Planes compact in neither order are interleaved, in the order that puts
	// x fastest (C when x comes after y, F when before), by the axes that come
	// after y there, such as the channels of a (t, z, y, x, c) array. cpp-zarr
	// then copies every chunk element by element into cache lines that the
	// threads copying the other indices write too (17 GB at 7.7 GB/s on 48
	// cores and 1.6 GB/s on 24, against 26 and 11 GB/s for one channel), and
	// the planes are gathered again. When x is stored faster than y, these
	// axes are read one index at a time instead, each read's planes compact
	// blocks with x fastest, if that decodes no chunk twice: no chunk spans two
	// indices of the axis, or the chunks store it slowest of their axes of
	// more than one index (channel-planar chunks, stored (t, c, z, y, x) by a
	// transpose codec), compressed by blosc or not at all, which a read of one
	// index decodes only the part of. Each read must hold at least a 64th of a
	// slab (16 MiB): a read costs about a millisecond beyond its data.
	// (Windows of 64 MB took about as long either way on 48 cores; of 270 MB
	// and 1 GB a third as long one channel at a time.)
	if (!first && !last) {
		const std::vector<uint64_t> stored = Zarr.get_chunkAxisOrder(); // slowest first
		std::vector<uint64_t> rank((size_t)n, 0);
		int64_t slowest = -1;
		for (size_t k = 0; k < stored.size(); k++) {
			if (stored[k] >= (uint64_t)n) continue;
			rank[stored[k]] = k;
			if (slowest < 0 && chunkExtent((int64_t)stored[k]) > 1) slowest = (int64_t)stored[k];
		}
		const bool partial = compressor == "blosc" || compressor == "none";
		const bool xAfterY = axes[X] > axes[Y];
		bool byIndex[5] = { false, false, false, false, false }, any = false;
		bool ok = stored.size() == (size_t)n && rank[axes[X]] > rank[axes[Y]];
		for (int i = Z; i <= T && ok; i++) {
			if (axes[i] < 0 || w.size[i] < 2 || (xAfterY ? axes[i] < axes[Y] : axes[i] > axes[Y])) continue;
			ok = chunkExtent(axes[i]) == 1 || (partial && axes[i] == slowest);
			byIndex[i] = any = true;
		}
		// With those axes at one index, the planes must be compact blocks with x
		// fastest in that order
		bool f = true, l = true;
		for (int64_t d = 0; d < n; d++) {
			bool single = d == axes[X] || d == axes[Y] || extent[d] == 1;
			for (int i = Z; i <= T; i++) single = single || (byIndex[i] && axes[i] == d);
			if (single) continue;
			if (d < hi) f = false;
			if (d > lo) l = false;
		}
		uint64_t each = bytes;
		for (int i = Z; i <= T; i++)
			if (byIndex[i]) each /= w.size[i];
		if (ok && any && (xAfterY ? l : f) && each >= minSlabBytes / 64) {
			w.cOrder = xAfterY;
			for (int i = Z; i <= T; i++) {
				if (!(w.byIndex[i] = byIndex[i])) continue;
				w.cuts[i].clear();
				for (uint64_t k = 0; k <= w.size[i]; k++) w.cuts[i].push_back(k);
				bytes /= w.size[i];
			}
		}
	}

	// Big windows are read in pieces through one buffer. A fresh buffer of the
	// whole window spends up to half the read having its pages zeroed by the
	// kernel; a reused one is zeroed once, and only a piece is held. Pieces
	// under twice minSlabBytes are read at once; bigger ones are cut into
	// slabs, along the slowest axis in the read's memory order first, each
	// slab starting and ending on chunk boundaries (no chunk is decoded twice)
	// and holding at least minSlabBytes. A slab that still holds twice that is
	// cut along the next axis too, so that the buffer stays near minSlabBytes
	// even when one step of the slowest axis is far bigger (one time point of
	// one channel of a (t, z, y, x, c) array of 17 GB holds 4.3 GB).
	// cut(i, least) cuts axis i into such pieces of at least least bytes.
	auto cut = [&](int i, uint64_t least) {
		const uint64_t a = (uint64_t)axes[i], chunk = chunkExtent(axes[i]);
		const uint64_t perUnit = bytes / w.size[i];
		const uint64_t rows = (least + perUnit * chunk - 1) / (perUnit * chunk);
		const uint64_t step = chunk * (rows > 0 ? rows : 1);
		w.cuts[i].assign(1, 0);
		for (uint64_t p = start[a]; p < end[a];) {
			const uint64_t next = (p / step + 1) * step < end[a] ? (p / step + 1) * step : end[a];
			w.cuts[i].push_back(next - start[a]);
			p = next;
		}
		bytes = perUnit * w.thickest(i);
	};
	for (int i : w.nesting()) {
		if (bytes < 2 * minSlabBytes) break;
		if (axes[i] < 0 || w.size[i] < 2 || w.byIndex[i]) continue;
		cut(i, minSlabBytes);
	}
	// Planes far bigger than slabs are cut into bricks as well, along y and x
	// at chunk boundaries, the slower of the two in the read's memory order
	// first, once one chunk row of whole planes along the axes above holds
	// brickFrom slabs (17 GB of the 67 MB planes of a (y, x, z) array of 256^3
	// chunks; 300 GB of 11985 x 48696 planes): each brick fills its rectangle
	// of the planes, and holds at least a slab. (Bricks of 1 and 2 GiB read
	// the 67 GB array above in 4.5-5.2 s on 48 cores, of 4 GiB in 5.1-5.7 s,
	// slabs of 17 GB in 6.0-6.5 s: cpp-zarr decodes big chunks in pieces
	// among its threads, so a brick of fewer chunks than threads still keeps
	// them busy.) A plane axis the window spans in fewer than 4 chunks is left
	// whole: its pieces would only add reads, and a piece one chunk deep holds
	// at most 3 chunks along it.
	const uint64_t brickFrom = 4;
	const std::vector<int> nest = w.order();
	if (bytes >= brickFrom * minSlabBytes)
		for (size_t k = nest.size() - 2; k < nest.size(); k++) {
			const int i = nest[k];
			if (bytes < 2 * minSlabBytes) break;
			const uint64_t a = (uint64_t)axes[i], chunk = chunkExtent(axes[i]);
			if (end[a] <= start[a] || (end[a] + chunk - 1) / chunk - start[a] / chunk < 4) continue;
			cut(i, minSlabBytes);
		}
	return "";
}

// Seconds a read spends in its parts, for the timing line
struct Times {
	double lists = 0, reads = 0, fills = 0;
};

// Reads the piece [lo[i], hi[i]) of the window along each image axis i into
// buf, missing chunks as the fill value, and fills its rectangle of its planes
// into Java's, each element converted by conv. Returns why the read failed, or
// "" (also when a Java exception is pending).
template <typename S, typename D, typename Conv>
std::string readSlab(JNIEnv* env, zarr& Zarr, const Window& w, const uint64_t* lo, const uint64_t* hi, void* buf,
                     const uint8_t* fillElem, jobjectArray dest, Conv conv, Times& times)
{
	const size_t n = w.start.size();
	std::vector<uint64_t> start = w.start, end = w.end, shape(n);
	for (int i = 0; i < 5; i++) {
		if (w.axes[i] < 0) continue;
		start[w.axes[i]] = w.start[w.axes[i]] + lo[i];
		end[w.axes[i]] = w.start[w.axes[i]] + hi[i];
	}
	for (size_t d = 0; d < n; d++) shape[d] = end[d] - start[d];
	const double t0 = now();
	Zarr.set_chunkInfo(start, end);
	const double t1 = now();
	const bool failed = parallelReadZarr(Zarr, buf, start, end, shape, sizeof(S) * 8, true, false, w.cOrder, fillElem) != 0;
	const double t2 = now();
	times.lists += t1 - t0;
	times.reads += t2 - t1;
	if (failed) {
		std::string why = Zarr.get_errString();
		while (!why.empty() && why.back() == '\n') why.pop_back();
		return why.empty() ? "Parallel Zarr read failed" : "Reading the Zarr failed: " + why;
	}

	std::vector<uint64_t> stride(n, 1);
	if (w.cOrder) for (int64_t d = (int64_t)n - 2; d >= 0; d--) stride[d] = stride[d + 1] * shape[d + 1];
	else for (size_t d = 1; d < n; d++) stride[d] = stride[d - 1] * shape[d - 1];
	uint64_t step[5];
	for (int i = 0; i < 5; i++) step[i] = w.axes[i] >= 0 ? stride[w.axes[i]] : 0;
	// The piece's planes: Java plane c + C*(z + Z*t), at its place in buf
	std::vector<uint64_t> idx, base;
	for (uint64_t t = lo[T]; t < hi[T]; t++)
		for (uint64_t z = lo[Z]; z < hi[Z]; z++)
			for (uint64_t c = lo[C]; c < hi[C]; c++) {
				idx.push_back(c + w.size[C] * (z + w.size[Z] * t));
				base.push_back((z - lo[Z]) * step[Z] + (c - lo[C]) * step[C] + (t - lo[T]) * step[T]);
			}
	const uint64_t width = hi[X] - lo[X], height = hi[Y] - lo[Y];
	const pfv::PlaneLayout layout = { width, height, step[X], step[Y], base.data(), lo[X], lo[Y], w.size[X] };
	const S* src = (const S*)buf;
	// About 256 MB of the rectangles a batch of pinned planes
	const uint64_t perBatch = pfv::planesPerBatch(width * height * sizeof(D));
	const double t3 = now();
	pfv::fillJavaPlanes<D>(env, dest, w.size[X] * w.size[Y], idx.data(), idx.size(),
		[&](D* const* dst, uint64_t k0, uint64_t count) { pfv::fillPlanes<S, D>(dst, k0, count, src, layout, conv); },
		[perBatch](uint64_t) { return perBatch; });
	times.fills += now() - t3;
	return "";
}

// Reads a window of an array of S elements into dest, Java planes of D, each
// element converted by conv. On failure a Java exception is pending and
// nullptr is returned.
template <typename S, typename D, typename Conv>
jobjectArray readToJava(JNIEnv* env, jstring fileName, jlongArray jStart, jlongArray jEnd, jintArray jAxes,
                        jobjectArray dest, Conv conv)
{
	if (jAxes == nullptr || env->GetArrayLength(jAxes) != 5) {
		pfv::throwRuntime(env, "The axes of a Zarr read must be {x, y, z, c, t}");
		return nullptr;
	}
	if (dest == nullptr) {
		pfv::throwRuntime(env, "No planes to read the Zarr into");
		return nullptr;
	}
	jint axes[5];
	env->GetIntArrayRegion(jAxes, 0, 5, axes);
	const std::vector<uint64_t> start = toVector(env, jStart);
	const std::vector<uint64_t> end = toVector(env, jEnd);
	pfv::NativePath path;
	if (!pfv::nativePath(env, fileName, path)) return nullptr;
	const double began = now();
	double planned = began;
	Times times;
	uint64_t reads = 0;
	bool cOrder = false;
	std::string problem, cuts;
	void* buf = nullptr;
	try {
		zarr Zarr(path.native);
		Window w;
		problem = plan(Zarr, start, end, axes, sizeof(S), w);
		planned = now();
		if (problem.empty() && (uint64_t)env->GetArrayLength(dest) != w.planes())
			problem = "Destination planes do not match the image slice count";
		if (problem.empty()) {
			reads = w.reads();
			cOrder = w.cOrder;
			cuts = cutsOf(w);
			uint8_t fillElem[16];
			fillValueElement(Zarr.get_fill_value(), Zarr.get_dtype(), fillElem);
			buf = malloc(w.thickest(X) * w.thickest(Y) * w.thickest(Z) * w.thickest(C) * w.thickest(T) * sizeof(S));
			if (buf == nullptr) problem = "Out of native memory reading the Zarr";
			// Every combination of one piece per axis, the slowest axis outermost
			const std::vector<int> nest = w.order();
			for (uint64_t r = 0; r < reads && problem.empty() && !env->ExceptionCheck(); r++) {
				uint64_t lo[5] = { 0, 0, 0, 0, 0 }, hi[5] = { 0, 0, 0, 0, 0 }, rest = r;
				for (size_t k = nest.size(); k-- > 0;) {
					const int i = nest[k];
					const uint64_t piece = rest % w.pieces(i);
					rest /= w.pieces(i);
					lo[i] = w.cuts[i][piece];
					hi[i] = w.cuts[i][piece + 1];
				}
				problem = readSlab<S, D>(env, Zarr, w, lo, hi, buf, fillElem, dest, conv, times);
			}
		}
	}
	catch (const std::string& why) {
		problem = pfv::zarrError(why);
	}
	catch (const std::exception& e) {
		problem = e.what();
	}
	catch (...) {
		problem = "Parallel Zarr read failed";
	}
	const double freed = now();
	free(buf);
	if (timingWanted())
		fprintf(stderr, "    [native zarr] open+plan %.3f s, %s slab(s) in %s order: chunk lists %.3f s, cpp-zarr reads %.3f s, "
			"copies into Java planes %.3f s, free %.3f s, total %.3f s (threads %d)%s%s\n",
			planned - began, std::to_string(reads).c_str(), cOrder ? "C" : "F", times.lists, times.reads, times.fills,
			now() - freed, now() - began, omp_get_max_threads(), cuts.empty() ? "" : ", cut: ", cuts.c_str());
	if (env->ExceptionCheck()) return nullptr;
	if (!problem.empty()) {
		pfv::throwRuntime(env, path.readable(problem));
		return nullptr;
	}
	return dest;
}

// Open a zarr's metadata and hand it to fn, turning every error into a Java
// exception; fn may itself throw std::string codes.
template <typename Fn>
void withZarr(JNIEnv* env, jstring fileName, Fn fn) {
	pfv::NativePath path;
	if (!pfv::nativePath(env, fileName, path)) return;
	try {
		zarr Zarr(path.native);
		fn(Zarr);
	}
	catch (const std::string& why) {
		pfv::throwRuntime(env, path.readable(pfv::zarrError(why)));
	}
	catch (const std::exception& e) {
		pfv::throwRuntime(env, path.readable(e.what()));
	}
	catch (...) {
		pfv::throwRuntime(env, "Reading the Zarr metadata failed");
	}
}

template <typename JavaT>
jobjectArray readZarr(JNIEnv* env, jstring fileName, jlongArray start, jlongArray end,
                      jintArray axes, jobjectArray dest)
{
	return readToJava<JavaT, JavaT>(env, fileName, start, end, axes, dest, pfv::Cast<JavaT, JavaT>());
}

// Signed integers: XOR-shift the sign bit into the unsigned range ImageJ
// expects; a Java-side calibration maps the values back.
template <typename UnsignedT, typename JavaT>
jobjectArray readZarrShifted(JNIEnv* env, jstring fileName, jlongArray start, jlongArray end,
	jintArray axes, UnsignedT mask, jobjectArray dest)
{
	return readToJava<UnsignedT, JavaT>(env, fileName, start, end, axes, dest, pfv::XorSign<UnsignedT, JavaT>{ mask });
}

// 32- and 64-bit integers and doubles are converted to float: ImageJ shows
// no other numeric types.
template <typename NativeT>
jobjectArray readZarrAsFloat(JNIEnv* env, jstring fileName, jlongArray start, jlongArray end,
	jintArray axes, jobjectArray dest)
{
	return readToJava<NativeT, float>(env, fileName, start, end, axes, dest, pfv::Cast<NativeT, float>());
}

} // namespace

#define PFV_ZARR_READ_ARGS (JNIEnv* env, jobject, jstring fileName, jlongArray start, jlongArray end, \
	jintArray axes, jobjectArray dest)

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrUINT8 PFV_ZARR_READ_ARGS
{
	return readZarr<int8_t>(env, fileName, start, end, axes, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrUINT16 PFV_ZARR_READ_ARGS
{
	return readZarr<int16_t>(env, fileName, start, end, axes, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrFLOAT PFV_ZARR_READ_ARGS
{
	return readZarr<float>(env, fileName, start, end, axes, dest);
}

// 64-bit images are converted to float: ImageStack has no double support.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrDOUBLE PFV_ZARR_READ_ARGS
{
	return readZarrAsFloat<double>(env, fileName, start, end, axes, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrINT8 PFV_ZARR_READ_ARGS
{
	return readZarrShifted<uint8_t, int8_t>(env, fileName, start, end, axes, (uint8_t)0x80u, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrINT16 PFV_ZARR_READ_ARGS
{
	return readZarrShifted<uint16_t, int16_t>(env, fileName, start, end, axes, (uint16_t)0x8000u, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrINT32 PFV_ZARR_READ_ARGS
{
	return readZarrAsFloat<int32_t>(env, fileName, start, end, axes, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrUINT32 PFV_ZARR_READ_ARGS
{
	return readZarrAsFloat<uint32_t>(env, fileName, start, end, axes, dest);
}

// ImageJ has no 64-bit integer type; converted to float like doubles are.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrINT64 PFV_ZARR_READ_ARGS
{
	return readZarrAsFloat<int64_t>(env, fileName, start, end, axes, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrUINT64 PFV_ZARR_READ_ARGS
{
	return readZarrAsFloat<uint64_t>(env, fileName, start, end, axes, dest);
}

// float16 and bfloat16 zarrs, converted to float (exact for both).
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadZarrFLOAT16
	(JNIEnv* env, jobject, jstring fileName, jlongArray start, jlongArray end,
	 jintArray axes, jboolean bfloat16, jobjectArray dest)
{
	return readToJava<uint16_t, float>(env, fileName, start, end, axes, dest, pfv::HalfToFloat{ bfloat16 != JNI_FALSE });
}

// The numpy-style dtype string, e.g. "<u2", "|u1" or "<f2". cpp-zarr gives
// Zarr v3 arrays ("uint16", ...) the same names; bfloat16 is "bfloat16".
JNIEXPORT jstring JNICALL Java_edu_abc_berkeley_ParallelReadNative_getZarrDtype
	(JNIEnv* env, jobject, jstring fileName)
{
	jstring rval = nullptr;
	withZarr(env, fileName, [&](zarr& Zarr) {
		rval = env->NewStringUTF(Zarr.get_dtype().c_str());
	});
	return rval;
}

JNIEXPORT jlong JNICALL Java_edu_abc_berkeley_ParallelReadNative_getZarrDataType
	(JNIEnv* env, jobject, jstring fileName)
{
	jlong bits = 0;
	withZarr(env, fileName, [&](zarr& Zarr) {
		bits = (jlong)(Zarr.dtypeBytes() * 8);
	});
	return bits;
}

// The array's shape, one value per axis.
JNIEXPORT jlongArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_getZarrDims
	(JNIEnv* env, jobject, jstring fileName)
{
	jlongArray rval = nullptr;
	withZarr(env, fileName, [&](zarr& Zarr) {
		const uint64_t n = Zarr.get_ndims();
		std::vector<jlong> dims((size_t)n);
		for (uint64_t d = 0; d < n; d++) dims[(size_t)d] = (jlong)Zarr.get_shape(d);
		rval = env->NewLongArray((jsize)n);
		if (rval != nullptr) env->SetLongArrayRegion(rval, 0, (jsize)n, dims.data());
	});
	return rval;
}

// The shape, as getZarrDims gives it, with the dtype and bits per element of
// getZarrDtype and getZarrDataType put in dtype[0] and bits[0]: one read of
// the metadata instead of three.
JNIEXPORT jlongArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_getZarrInfo
	(JNIEnv* env, jobject, jstring fileName, jobjectArray dtype, jlongArray bits)
{
	if (dtype == nullptr || bits == nullptr || env->GetArrayLength(dtype) < 1 || env->GetArrayLength(bits) < 1) {
		pfv::throwRuntime(env, "getZarrInfo needs a String[1] for the dtype and a long[1] for the bits");
		return nullptr;
	}
	jlongArray rval = nullptr;
	withZarr(env, fileName, [&](zarr& Zarr) {
		const uint64_t n = Zarr.get_ndims();
		std::vector<jlong> dims((size_t)n);
		for (uint64_t d = 0; d < n; d++) dims[(size_t)d] = (jlong)Zarr.get_shape(d);
		jstring name = env->NewStringUTF(Zarr.get_dtype().c_str());
		if (name == nullptr) return;
		env->SetObjectArrayElement(dtype, 0, name);
		const jlong b = (jlong)(Zarr.dtypeBytes() * 8);
		env->SetLongArrayRegion(bits, 0, 1, &b);
		rval = env->NewLongArray((jsize)n);
		if (rval != nullptr) env->SetLongArrayRegion(rval, 0, (jsize)n, dims.data());
	});
	return rval;
}

// For tests: the least a slab of a big read holds (see plan).
JNIEXPORT void JNICALL Java_edu_abc_berkeley_ParallelReadNative_setZarrSlabBytes
	(JNIEnv*, jclass, jlong bytes)
{
	minSlabBytes = bytes > 0 ? (uint64_t)bytes : 1;
}

// For tests: how a read of the window [start, end) would be cut (see
// describe), or why it cannot be done.
JNIEXPORT jstring JNICALL Java_edu_abc_berkeley_ParallelReadNative_describeZarrRead
	(JNIEnv* env, jclass, jstring fileName, jlongArray jStart, jlongArray jEnd, jintArray jAxes)
{
	if (jAxes == nullptr || env->GetArrayLength(jAxes) != 5) {
		pfv::throwRuntime(env, "The axes of a Zarr read must be {x, y, z, c, t}");
		return nullptr;
	}
	jint axes[5];
	env->GetIntArrayRegion(jAxes, 0, 5, axes);
	const std::vector<uint64_t> start = toVector(env, jStart);
	const std::vector<uint64_t> end = toVector(env, jEnd);
	jstring rval = nullptr;
	withZarr(env, fileName, [&](zarr& Zarr) {
		Window w;
		const std::string problem = plan(Zarr, start, end, axes, Zarr.dtypeBytes(), w);
		rval = env->NewStringUTF((problem.empty() ? describe(w) : problem).c_str());
	});
	return rval;
}

// Runs when Java loads this library; see primeUnwinder in jni_marshal.h.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*)
{
	pfv::primeUnwinder();
	return JNI_VERSION_1_6;
}
