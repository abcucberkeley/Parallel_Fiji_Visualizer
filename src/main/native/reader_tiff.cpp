// TIFF half of libParallelReadC: JNI bindings for edu.abc.berkeley.ParallelReadNative.
// Delegates to cpp-tiff; this translation unit must only see cpp-tiff headers
// (see CMakeLists.txt -- helperfunctions.h clashes between the upstreams).
//
// getTiffInfo describes a file from one open and one walk of its directories
// (cpp-tiff's TiffInfo, as a long[] for Java). Every read takes that
// description back, so reading a few slices of a deep stack does not walk the
// directories again. A read returns ImageJ planes for the file's slices
// [zStart, zEnd): one per slice, or for images with several samples per pixel
// one per sample, a slice's samples one after another (as ImageJ orders
// channels). 8-bit color is instead packed into ImageJ RGB pixels by RGB8.
//
// ImageJ stacks (TiffInfo.imageJ: one directory followed by every slice,
// uncompressed, which is how ImageJ saves stacks over 4 GB) are read here, not
// by cpp-tiff, whose reader for them is one stream on one thread: every thread
// reads its own stretch of the slices through its own handle (raw_file.h),
// straight into Java's planes when they take the samples as stored
// (readIntoPlanes), otherwise through one reused buffer of whole slices that
// the conversions below empty into the planes. A file whose first directory
// this code cannot follow is read by cpp-tiff as before.
//
// With the environment variable PFV_TIMING set, every read prints one line
// on stderr with the seconds of its parts.

#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <vector>

#include "parallelreadtiff.h"
#include "helperfunctions.h" // cpp-tiff's

#ifdef __linux__
#include <sys/mman.h>
#include <unistd.h>
#endif

#include "jni_marshal.h"
#include "raw_file.h"
#include "edu_abc_berkeley_ParallelReadNative.h"

namespace {

// TiffInfo's fields in the order of Java's long[] (ParallelReadNative.TIFF_*)
enum { I_WIDTH, I_HEIGHT, I_SLICES, I_DIRECTORIES, I_BITS, I_SAMPLES, I_SAMPLE_FORMAT, I_STRIP_SIZE,
	I_COMPRESSION, I_PLANAR, I_TILED, I_IMAGEJ, I_IMAGEJ_IMAGES, I_COUNT };

const char* const readFailed = "Parallel TIFF read failed (is the file damaged?)";

// The most bytes of slices a read of an ImageJ stack converts through one
// native buffer, at least one slice
const uint64_t imageJSlabBytes = (uint64_t)1 << 30;
// cpp-tiff reads in slabs of at least this many bytes, in at most
// maxTiffSlabs of them, and in at most one per sliceBytesPerSlab of a slice
// (but 2 if that makes fewer; see tiffSlabSlices)
const uint64_t leastTiffSlabBytes = (uint64_t)4 << 30;
const uint64_t maxTiffSlabs = 8;
const uint64_t sliceBytesPerSlab = (uint64_t)1 << 20;
// For tests (setTiffSlabBytes): both of the slab sizes above, when not 0
uint64_t slabOverride = 0;

bool timing() {
	static const bool on = getenv("PFV_TIMING") != nullptr;
	return on;
}

double now() {
	return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

bool infoFromJava(JNIEnv* env, jlongArray a, TiffInfo& info) {
	if (a == nullptr || env->GetArrayLength(a) != I_COUNT) {
		pfv::throwRuntime(env, "Invalid TIFF description: get it from getTiffInfo");
		return false;
	}
	jlong v[I_COUNT];
	env->GetLongArrayRegion(a, 0, I_COUNT, v);
	info.x = (uint64_t)v[I_WIDTH];
	info.y = (uint64_t)v[I_HEIGHT];
	info.z = (uint64_t)v[I_SLICES];
	info.directories = (uint64_t)v[I_DIRECTORIES];
	info.bits = (uint64_t)v[I_BITS];
	info.samples = (uint64_t)v[I_SAMPLES];
	info.sampleFormat = (uint16_t)v[I_SAMPLE_FORMAT];
	info.stripSize = (uint64_t)v[I_STRIP_SIZE];
	info.compression = (uint16_t)v[I_COMPRESSION];
	info.planar = v[I_PLANAR] != 0;
	info.tiled = v[I_TILED] != 0;
	info.imageJ = v[I_IMAGEJ] != 0;
	info.imageJImages = (uint64_t)v[I_IMAGEJ_IMAGES];
	return true;
}

// Threads for reading slices [zStart, zEnd): about one per MiB. Every core on
// a small file is slower than a few (300 2048x2048 16-bit files: 1.08 s with
// 48 threads, 0.32 s with 6), as each thread opens the file.
uint64_t threadsFor(const TiffInfo& info, jlong zStart, jlong zEnd) {
	const uint64_t slices = zEnd > zStart ? (uint64_t)(zEnd - zStart) : 1;
	const uint64_t bytes = slices * info.x * info.y * info.samples * (info.bits / 8);
	return (bytes + (1u << 20) - 1) >> 20;
}

// Frees buf (from malloc, bytes long). The kernel hands back a big buffer's
// pages on the thread that frees it, at 20-40 GB/s (0.2-0.4 s for 8.4 GB,
// 2.3-3.5 s for 67 GB); on Linux every thread first hands back a part of them
// (MADV_DONTNEED, which threads do side by side), and free then only unmaps
// the addresses: 0.05-0.07 s for 8.4 GB, 0.5-0.6 s for 67 GB on 48 cores.
// Elsewhere free does it all.
void release(void* buf, uint64_t bytes) {
#ifdef __linux__
	const uint64_t piece = (uint64_t)64 << 20;
	const long page = sysconf(_SC_PAGESIZE);
	if (buf != nullptr && bytes >= 2 * piece && page > 0) {
		const uint64_t a = ((uint64_t)(uintptr_t)buf + page - 1) / page * page, e = ((uint64_t)(uintptr_t)buf + bytes) / page * page;
		const int64_t pieces = (int64_t)((e - a + piece - 1) / piece);
		#pragma omp parallel for schedule(static)
		for (int64_t k = 0; k < pieces; k++) {
			const uint64_t p = a + (uint64_t)k * piece;
			madvise((void*)(uintptr_t)p, (size_t)(p + piece < e ? piece : e - p), MADV_DONTNEED);
		}
	}
#else
	(void)bytes;
#endif
	free(buf);
}

// A read: the file, its description, and its slices [zStart, zStart + slices)
struct Request {
	std::string path;
	TiffInfo info;
	uint64_t zStart = 0, slices = 0;

	uint64_t sliceBytes() const { return info.x * info.y * info.samples * (info.bits / 8); }
	// Java planes: one per slice, or per sample of each slice
	uint64_t planes() const { return slices * info.samples; }
};

// The read of slices [zStart, zEnd), checked to lie in the file and to have
// samples elemBytes wide. On failure a Java exception is pending.
bool request(JNIEnv* env, jstring fileName, jlongArray jInfo, jlong zStart, jlong zEnd, uint64_t elemBytes, Request& r) {
	if (!infoFromJava(env, jInfo, r.info)) return false;
	if (zStart < 0 || zEnd <= zStart || (uint64_t)zEnd > r.info.z) {
		pfv::throwRuntime(env, "The slices to read lie outside the TIFF");
		return false;
	}
	if (r.info.bits != elemBytes * 8) {
		pfv::throwRuntime(env, "The TIFF's data type does not match the read");
		return false;
	}
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return false;
	r.path = fName;
	env->ReleaseStringUTFChars(fileName, fName);
	r.zStart = (uint64_t)zStart;
	r.slices = (uint64_t)(zEnd - zStart);
	return true;
}

// dest if it holds the read's planes (planes of the image's x*y pixels), or
// new planes if it is null; on failure null with a Java exception pending
template <typename D>
jobjectArray planesFor(JNIEnv* env, const Request& r, uint64_t planes, jobjectArray dest) {
	if (dest == nullptr) // allocated (and left zero) a batch at a time
		return pfv::slicesToJavaWith<D>(env, r.info.x * r.info.y, planes, nullptr, [](D* const*, uint64_t, uint64_t) {});
	if ((uint64_t)env->GetArrayLength(dest) == planes) return dest;
	pfv::throwRuntime(env, "Destination planes do not match the image slice count");
	return nullptr;
}

// ---- ImageJ stacks ----

// Where an ImageJ stack's slices lie: one after another from the first
// strip's offset, in the file's byte order
struct ImageJStack {
	bool found = false;
	uint64_t offset = 0;
	bool swap = false; // the file's byte order is not this machine's
	int64_t fileBytes = -1;
};

uint64_t fromBytes(const uint8_t* p, unsigned n, bool bigEndian) {
	uint64_t v = 0;
	for (unsigned i = 0; i < n; i++) v = bigEndian ? (v << 8) | p[i] : v | (uint64_t)p[i] << (8 * i);
	return v;
}

bool hostBigEndian() {
	const uint16_t one = 1;
	uint8_t first;
	memcpy(&first, &one, 1);
	return first == 0;
}

// The first strip's offset and the byte order, from the first directory of a
// classic TIFF or a BigTIFF (StripOffsets as SHORT, LONG or LONG8, inline or
// at an offset). False if the directory is not as expected.
bool readFirstDirectory(pfv::RawFile& f, ImageJStack& s) {
	uint8_t h[16];
	if (!f.readAt(h, 8, 0) || h[0] != h[1] || (h[0] != 'I' && h[0] != 'M')) return false;
	const bool be = h[0] == 'M';
	const uint64_t version = fromBytes(h + 2, 2, be);
	const bool big = version == 43;
	uint64_t ifd;
	if (version == 42) ifd = fromBytes(h + 4, 4, be);
	else if (big && fromBytes(h + 4, 2, be) == 8 && f.readAt(h + 8, 8, 8)) ifd = fromBytes(h + 8, 8, be);
	else return false;
	// A directory: the number of entries, then each entry's tag, type, count
	// and value, or the value's offset when it does not fit
	const unsigned countBytes = big ? 8 : 2, entryBytes = big ? 20 : 12, valueBytes = big ? 8 : 4;
	uint8_t c[8];
	if (!f.readAt(c, countBytes, ifd)) return false;
	const uint64_t entries = fromBytes(c, countBytes, be);
	if (entries == 0 || entries > 65535) return false;
	std::vector<uint8_t> e((size_t)(entries * entryBytes));
	if (!f.readAt(e.data(), e.size(), ifd + countBytes)) return false;
	for (uint64_t i = 0; i < entries; i++) {
		const uint8_t* q = e.data() + i * entryBytes;
		if (fromBytes(q, 2, be) != 273) continue; // StripOffsets
		const uint64_t type = fromBytes(q + 2, 2, be), count = fromBytes(q + 4, valueBytes, be);
		const unsigned size = type == 3 ? 2 : type == 4 ? 4 : type == 16 ? 8 : 0;
		if (size == 0 || count == 0) return false;
		const uint8_t* value = q + 4 + valueBytes;
		uint8_t first[8];
		if (count > valueBytes / size) {
			if (!f.readAt(first, size, fromBytes(value, valueBytes, be))) return false;
			value = first;
		}
		s.offset = fromBytes(value, size, be);
		s.swap = be != hostBigEndian();
		return true;
	}
	return false;
}

// The layout of r's file if it is an ImageJ stack this code can read; found
// is false for every other file, which cpp-tiff reads.
ImageJStack imageJStack(const Request& r) {
	ImageJStack s;
	if (!r.info.imageJ) return s;
	pfv::RawFile f(r.path.c_str());
	if (f.isOpen() && readFirstDirectory(f, s)) {
		s.found = true;
		s.fileBytes = f.size();
	}
	return s;
}

// Whether the file holds every byte of the read: a file cut short fails
// before anything is read (a read that comes up short fails as well)
bool holdsRead(JNIEnv* env, const Request& r, const ImageJStack& s) {
	if (s.fileBytes < 0 || s.offset + (r.zStart + r.slices) * r.sliceBytes() <= (uint64_t)s.fileBytes) return true;
	pfv::throwRuntime(env, readFailed);
	return false;
}

// The handles of a read, one per thread of its parallel regions, each opened
// by its thread when first used and closed at the end of the read
class Handles {
	const std::string& path;
	std::vector<std::unique_ptr<pfv::RawFile>> files;
public:
	explicit Handles(const std::string& path) : path(path), files((size_t)omp_get_max_threads()) {}

	// The calling thread's handle, or null if it cannot be opened
	pfv::RawFile* mine() {
		std::unique_ptr<pfv::RawFile>& f = files[(size_t)omp_get_thread_num()];
		if (!f) f.reset(new (std::nothrow) pfv::RawFile(path.c_str()));
		return f && f->isOpen() ? f.get() : nullptr;
	}
};

// Reverses the bytes of each sample (elemBytes wide) of bytes bytes at p
void swapSamples(void* p, uint64_t bytes, unsigned elemBytes) {
	if (elemBytes == 2) {
		uint16_t* s = (uint16_t*)p;
		for (uint64_t i = 0, n = bytes / 2; i < n; i++) s[i] = __builtin_bswap16(s[i]);
	}
	else if (elemBytes == 4) {
		uint32_t* s = (uint32_t*)p;
		for (uint64_t i = 0, n = bytes / 4; i < n; i++) s[i] = __builtin_bswap32(s[i]);
	}
	else if (elemBytes == 8) {
		uint64_t* s = (uint64_t*)p;
		for (uint64_t i = 0, n = bytes / 8; i < n; i++) s[i] = __builtin_bswap64(s[i]);
	}
}

// Pieces of the file a thread reads at a time: small enough that its bytes
// are swapped while they are still in the cache (4 MiB pieces made the read
// of the 40 GB stack 10-25% slower)
const uint64_t pieceBytes = (uint64_t)1 << 20;

// Reads the bytes bytes of the file at at into buf with every thread, each
// through its own handle a contiguous run of pieces in file order (a
// sequential stream, which the kernel reads ahead), the bytes of each piece
// swapped (samples elemBytes wide) right after it is read. False if a read
// fails or the file ends first.
bool readRange(Handles& files, uint64_t at, void* buf, uint64_t bytes, unsigned elemBytes, bool swap) {
	const int64_t pieces = (int64_t)((bytes + pieceBytes - 1) / pieceBytes);
	std::atomic<bool> failed(false);
	#pragma omp parallel for schedule(static)
	for (int64_t k = 0; k < pieces; k++) {
		if (failed.load(std::memory_order_relaxed)) continue;
		const uint64_t a = (uint64_t)k * pieceBytes, n = a + pieceBytes < bytes ? pieceBytes : bytes - a;
		char* d = (char*)buf + a;
		pfv::RawFile* f = files.mine();
		if (f == nullptr || !f->readAt(d, n, at + a)) failed = true;
		else if (swap) swapSamples(d, n, elemBytes);
	}
	return !failed;
}

// A read straight into Java's planes pins them a batch at a time, and keeps
// the JVM's collector out until the batch is done. So batches are sized to
// take about batchSeconds at the rate of the batch before (the first is
// small, and none more than 8 times the one before): other threads that need
// a collection meanwhile wait no longer than that, even on slow storage,
// while a fast read is not cut into many batches, each of which waits for its
// slowest thread (at 256 MB a batch, the 40 GB stack read from the page cache
// took 1.0-2.3 s on Java 8 instead of 0.8-0.9 s in one batch).
const double batchSeconds = 0.1;
const uint64_t firstBatchBytes = (uint64_t)64 << 20;
// Lanes start on multiples of this, and a lane's batch reads a multiple of it
// unless it ends at a plane's end or the lane's (every piece holds whole
// samples)
const uint64_t laneAlign = 4096;

// Slices of r's ImageJ stack into Java's planes as they are stored (T as on
// disk, one sample per pixel): read straight into the pinned planes. The
// read's bytes are cut into lanes, one per thread, and every batch continues
// each lane where the batch before stopped, so that each thread's handle
// reads one sequential stream from the start of the read to its end, which
// the kernel reads ahead. (With each batch cut afresh into runs, every thread
// moved on to a new place in the file at each batch and its read-ahead started
// over: the 40 GB stack read cold in 16-18 s instead of 10-12 s, the storage's
// own rate on that day.)
template <typename T>
jobjectArray readIntoPlanes(JNIEnv* env, const Request& r, const ImageJStack& s, jobjectArray dest) {
	if (!holdsRead(env, r, s)) return nullptr;
	jobjectArray out = planesFor<T>(env, r, r.slices, dest);
	if (out == nullptr) return nullptr;
	const double t0 = now();
	const uint64_t n = r.info.x * r.info.y, planeBytes = n * sizeof(T), bytes = r.slices * planeBytes;
	const uint64_t at = s.offset + r.zStart * planeBytes; // where the read starts in the file
	// Lane l is the read's bytes [cur[l], end[l]); a batch reads [cur[l], stop[l])
	const uint64_t lanes = (uint64_t)omp_get_max_threads();
	std::vector<uint64_t> cur(lanes), stop(lanes), end(lanes), first(lanes);
	for (uint64_t l = 0; l < lanes; l++) cur[l] = bytes / lanes * l / laneAlign * laneAlign;
	for (uint64_t l = 0; l < lanes; l++) end[l] = l + 1 < lanes ? cur[l + 1] : bytes;
	// The most planes a lane's batch touches, so that a batch pins no more
	// than maxBatchPlanes
	const uint64_t lanePlanes = pfv::maxBatchPlanes / lanes > 1 ? pfv::maxBatchPlanes / lanes : 1;
	uint64_t perLane = firstBatchBytes / lanes; // the next batch's bytes of each lane
	Handles files(r.path);
	std::atomic<bool> failed(false);
	std::vector<uint64_t> idx; // the batch's planes, in file order
	uint64_t batches = 0, largest = 0;
	for (;;) {
		perLane = perLane < laneAlign ? laneAlign : perLane / laneAlign * laneAlign;
		idx.clear();
		uint64_t batchBytes = 0;
		for (uint64_t l = 0; l < lanes; l++) {
			const uint64_t planeCap = (cur[l] / planeBytes + lanePlanes) * planeBytes;
			stop[l] = cur[l] + perLane < end[l] ? cur[l] + perLane : end[l];
			if (stop[l] > planeCap) stop[l] = planeCap;
			if (stop[l] <= cur[l]) continue;
			batchBytes += stop[l] - cur[l];
			for (uint64_t p = cur[l] / planeBytes; p <= (stop[l] - 1) / planeBytes; p++)
				if (idx.empty() || idx.back() != p) idx.push_back(p);
		}
		if (idx.empty()) break;
		for (uint64_t l = 0; l < lanes; l++)
			if (stop[l] > cur[l]) first[l] = (uint64_t)(std::lower_bound(idx.begin(), idx.end(), cur[l] / planeBytes) - idx.begin());
		double t = 0;
		const bool pinned = pfv::fillJavaPlanes<T>(env, out, n, idx.data(), idx.size(), [&](T* const* dst, uint64_t, uint64_t) {
			const double a = now();
			// Thread l reads lane l in every batch (and so through the same handle)
			#pragma omp parallel for schedule(static, 1)
			for (int64_t l = 0; l < (int64_t)lanes; l++) {
				uint64_t k = first[l];
				for (uint64_t pos = cur[l]; pos < stop[l] && !failed.load(std::memory_order_relaxed);) {
					const uint64_t in = pos % planeBytes;
					while (idx[k] < pos / planeBytes) k++;
					uint64_t m = planeBytes - in < pieceBytes ? planeBytes - in : pieceBytes;
					if (m > stop[l] - pos) m = stop[l] - pos;
					char* d = (char*)dst[k] + in;
					pfv::RawFile* f = files.mine();
					if (f == nullptr || !f->readAt(d, m, at + pos)) failed = true;
					else if (s.swap) swapSamples(d, m, sizeof(T));
					pos += m;
				}
			}
			t = now() - a;
		}, [&](uint64_t) { return (uint64_t)idx.size(); });
		if (!pinned) return nullptr;
		if (failed) break;
		cur = stop;
		batches++;
		if (batchBytes > largest) largest = batchBytes;
		const double next = t > 0 ? batchBytes / lanes * batchSeconds / t : 8.0 * perLane;
		perLane = next < 8.0 * perLane ? (uint64_t)next : 8 * perLane;
	}
	if (timing())
		fprintf(stderr, "    [native tiff] ImageJ stack read into Java planes %.3f s (%.2f GB, %llu batches of up to %.2f GB, threads %d)\n",
			now() - t0, bytes / 1e9, (unsigned long long)batches, largest / 1e9, omp_get_max_threads());
	if (failed) {
		pfv::throwRuntime(env, readFailed);
		return nullptr;
	}
	return out;
}

// Slices of r's ImageJ stack read a slab at a time through one reused buffer
// of whole slices (at most imageJSlabBytes, at least one slice); after each
// slab, deliver(buf, s0, s1) moves the read's slices [s0, s1) from buf into
// Java's planes. False, with a Java exception pending, if a read or a delivery fails.
template <typename Deliver>
bool readImageJSlabs(JNIEnv* env, const Request& r, const ImageJStack& s, Deliver deliver) {
	if (!holdsRead(env, r, s)) return false;
	const uint64_t sliceBytes = r.sliceBytes();
	uint64_t per = (slabOverride > 0 ? slabOverride : imageJSlabBytes) / sliceBytes;
	if (per < 1) per = 1;
	if (per > r.slices) per = r.slices;
	void* buf = malloc((size_t)(per * sliceBytes));
	if (buf == nullptr) {
		pfv::throwRuntime(env, "Not enough native memory to read the TIFF");
		return false;
	}
	Handles files(r.path);
	bool ok = true;
	double tRead = 0, tDeliver = 0;
	for (uint64_t s0 = 0; s0 < r.slices && ok; s0 += per) {
		const uint64_t s1 = s0 + per < r.slices ? s0 + per : r.slices;
		const double a = now();
		ok = readRange(files, s.offset + (r.zStart + s0) * sliceBytes, buf, (s1 - s0) * sliceBytes, (unsigned)(r.info.bits / 8), s.swap);
		const double b = now();
		if (!ok) pfv::throwRuntime(env, readFailed);
		else ok = deliver(buf, s0, s1);
		tRead += b - a;
		tDeliver += now() - b;
	}
	const double t1 = now();
	release(buf, per * sliceBytes);
	if (timing())
		fprintf(stderr, "    [native tiff] ImageJ stack read through a %.2f GB buffer: reads %.3f s, into Java planes %.3f s, free %.3f s (%.2f GB, threads %d)\n",
			per * sliceBytes / 1e9, tRead, tDeliver, now() - t1, r.slices * sliceBytes / 1e9, omp_get_max_threads());
	return ok;
}

// ---- Every read ----

// Moves the read's slices [s0, s1), stored in buf as in the file ((z, y, x[,
// sample]) row-major), into their Java planes of dest as D, one plane per
// slice, or one per sample of each slice (plane s + samples*z), each element
// converted by conv on its way. The samples are split straight into Java's
// arrays, with no intermediate copy. False with a Java exception pending if
// a plane is missing or the wrong size.
template <typename S, typename D, typename Conv>
bool slabToJava(JNIEnv* env, const TiffInfo& info, const void* buf, uint64_t s0, uint64_t s1, jobjectArray dest, Conv conv) {
	const uint64_t samples = info.samples, n = info.x * info.y, planes = (s1 - s0) * samples;
	std::vector<uint64_t> idx(planes), base(planes);
	for (uint64_t p = 0; p < planes; p++) {
		idx[p] = s0 * samples + p;
		base[p] = (p / samples) * n * samples + p % samples;
	}
	const pfv::PlaneLayout layout = { info.x, info.y, samples, info.x * samples, base.data() };
	const S* src = (const S*)buf;
	return pfv::fillJavaPlanes<D>(env, dest, n, idx.data(), planes,
		[&](D* const* dst, uint64_t k0, uint64_t count) { pfv::fillPlanes<S, D>(dst, k0, count, src, layout, conv); });
}

// The same for 8-bit color: the read's slices [s0, s1) packed into ImageJ RGB
// pixels (0xff000000 | r<<16 | g<<8 | b; alpha, if present, dropped), one
// plane per slice, straight into Java's arrays
bool rgbSlabToJava(JNIEnv* env, const TiffInfo& info, const void* buf, uint64_t s0, uint64_t s1, jobjectArray dest) {
	const uint64_t slices = s1 - s0, n = info.x * info.y, samples = info.samples;
	std::vector<uint64_t> idx(slices);
	for (uint64_t k = 0; k < slices; k++) idx[k] = s0 + k;
	const uint8_t* src = (const uint8_t*)buf;
	return pfv::fillJavaPlanes<int32_t>(env, dest, n, idx.data(), slices, [&](int32_t* const* dst, uint64_t first, uint64_t count) {
		const uint64_t block = 1u << 16, per = (n + block - 1) / block;
		const int64_t total = (int64_t)(count * per);
		#pragma omp parallel for schedule(static) if(count * n * 4 > (1u << 20))
		for (int64_t k = 0; k < total; k++) {
			const uint64_t p = (uint64_t)k / per, i0 = ((uint64_t)k % per) * block, i1 = i0 + block < n ? i0 + block : n;
			const uint8_t* s = src + (first + p) * n * samples;
			int32_t* d = dst[p];
			for (uint64_t i = i0; i < i1; i++) {
				const uint8_t* q = s + i * samples;
				d[i] = (int32_t)(0xFF000000u | ((uint32_t)q[0] << 16) | ((uint32_t)q[1] << 8) | (uint32_t)q[2]);
			}
		}
	});
}

// Slices of a cpp-tiff read per slab, or all of them for a read in one piece.
// A buffer of the whole image holds the image twice beside Java's planes (a
// 67 GB TIFF peaked at 197 GB) and has every page zeroed by the kernel; one
// buffer reused slab after slab touches its pages once. But every cpp-tiff
// call makes each of its threads open the file and walk the directories to
// the slab's first slice, about 17 us per directory on 48 cores (10,000
// slices of 1.8 MB read in 0.9-1.0 s in one piece, 1.1 s in 2 slabs, 1.4 s
// in 4, 2.1 s in 8), against a decode of about 20 GB/s; so the smaller the
// slices, the fewer the slabs: at most one per MiB of a slice, but 2 for any
// read of 8 GiB or more. Measured on 48 cores, Java 8, the 67 GB TIFF of
// 8 MB slices read natively in 5.4 s in one piece, 4.8-5.1 s in 2 slabs,
// 4.7-4.8 s in 4 and 5.2-5.3 s in 8, its first open peaking at 197, 167, 150
// and 143 GB; the 10,000 slices in 1.3-1.5 s in one piece and 1.5 s in 2.
uint64_t tiffSlabSlices(const Request& r) {
	const uint64_t sliceBytes = r.sliceBytes(), bytes = r.slices * sliceBytes;
	uint64_t slabs = bytes / (slabOverride > 0 ? slabOverride : leastTiffSlabBytes);
	if (slabs > maxTiffSlabs) slabs = maxTiffSlabs;
	const uint64_t spread = sliceBytes / sliceBytesPerSlab > 2 ? sliceBytes / sliceBytesPerSlab : 2;
	if (slabOverride == 0 && slabs > spread) slabs = spread;
	if (slabs <= 1) return r.slices;
	return (r.slices + slabs - 1) / slabs;
}

// Reads r's slices as stored and hands them to deliver(buf, s0, s1) (see
// readImageJSlabs): an ImageJ stack a slab at a time through raw reads, any
// other file through cpp-tiff, a large read a slab at a time through one
// reused buffer (tiffSlabSlices), a small one in one piece into a buffer
// cpp-tiff allocates. False with a Java exception pending on failure.
template <typename Deliver>
bool readSlabs(JNIEnv* env, const Request& r, const ImageJStack& s, Deliver deliver) {
	if (s.found) return readImageJSlabs(env, r, s, deliver);
	const uint64_t sliceBytes = r.sliceBytes(), per = tiffSlabSlices(r);
	const double t0 = now();
	void* buf = nullptr;
	if (per < r.slices && (buf = malloc((size_t)(per * sliceBytes))) == nullptr) {
		pfv::throwRuntime(env, "Not enough native memory to read the TIFF");
		return false;
	}
	bool ok = true;
	uint64_t slabs = 0;
	double tRead = 0, tDeliver = 0;
	for (uint64_t s0 = 0; s0 < r.slices && ok; s0 += per, slabs++) {
		const uint64_t s1 = s0 + per < r.slices ? s0 + per : r.slices;
		const double a = now();
		void* got = readTiffParallelInfo(r.path.c_str(), r.info, buf, 0, { r.zStart + s0, r.zStart + s1 });
		const double b = now();
		if (got == nullptr) {
			pfv::throwRuntime(env, readFailed);
			ok = false;
		}
		else {
			buf = got;
			ok = deliver(buf, s0, s1);
		}
		tRead += b - a;
		tDeliver += now() - b;
	}
	const double t1 = now();
	release(buf, per * sliceBytes);
	if (timing())
		fprintf(stderr, "    [native tiff] cpp-tiff read in %llu slab(s) through a %.2f GB buffer: reads %.3f s, into Java planes %.3f s, free %.3f s, total %.3f s (%.2f GB, threads %d)\n",
			(unsigned long long)slabs, per * sliceBytes / 1e9, tRead, tDeliver, now() - t1, now() - t0, r.slices * sliceBytes / 1e9, omp_get_max_threads());
	return ok;
}

// r's slices into Java planes of D, each sample converted by conv
template <typename S, typename D, typename Conv>
jobjectArray readConverted(JNIEnv* env, const Request& r, const ImageJStack& s, jobjectArray dest, Conv conv) {
	jobjectArray out = planesFor<D>(env, r, r.planes(), dest);
	if (out == nullptr) return nullptr;
	const bool ok = readSlabs(env, r, s, [&](const void* buf, uint64_t s0, uint64_t s1) {
		return slabToJava<S, D>(env, r.info, buf, s0, s1, out, conv);
	});
	return ok ? out : nullptr;
}

template <typename T>
jobjectArray readTiff(JNIEnv* env, jstring fileName, jlongArray jInfo, jlong zStart, jlong zEnd, jobjectArray dest) {
	Request r;
	if (!request(env, fileName, jInfo, zStart, zEnd, sizeof(T), r)) return nullptr;
	const pfv::ThreadCap cap(threadsFor(r.info, zStart, zEnd));
	const ImageJStack s = imageJStack(r);
	if (s.found && r.info.samples == 1) return readIntoPlanes<T>(env, r, s, dest);
	return readConverted<T, T>(env, r, s, dest, pfv::Cast<T, T>());
}

// Signed integers: XOR-shift the sign bit into the unsigned range ImageJ
// expects; a Java-side calibration maps the values back.
template <typename UnsignedT, typename JavaT>
jobjectArray readTiffShifted(JNIEnv* env, jstring fileName, jlongArray jInfo, jlong zStart, jlong zEnd,
	UnsignedT mask, jobjectArray dest)
{
	Request r;
	if (!request(env, fileName, jInfo, zStart, zEnd, sizeof(UnsignedT), r)) return nullptr;
	const pfv::ThreadCap cap(threadsFor(r.info, zStart, zEnd));
	return readConverted<UnsignedT, JavaT>(env, r, imageJStack(r), dest, pfv::XorSign<UnsignedT, JavaT>{ mask });
}

// 32- and 64-bit integers and doubles are converted to float: ImageJ shows
// no other numeric types.
template <typename NativeT>
jobjectArray readTiffAsFloat(JNIEnv* env, jstring fileName, jlongArray jInfo, jlong zStart, jlong zEnd, jobjectArray dest) {
	Request r;
	if (!request(env, fileName, jInfo, zStart, zEnd, sizeof(NativeT), r)) return nullptr;
	const pfv::ThreadCap cap(threadsFor(r.info, zStart, zEnd));
	return readConverted<NativeT, float>(env, r, imageJStack(r), dest, pfv::Cast<NativeT, float>());
}

} // namespace

#define PFV_TIFF_READ_ARGS (JNIEnv* env, jobject, jstring fileName, jlongArray info, jlong zStart, jlong zEnd, jobjectArray dest)

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffUINT8 PFV_TIFF_READ_ARGS
{
	return readTiff<int8_t>(env, fileName, info, zStart, zEnd, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffUINT16 PFV_TIFF_READ_ARGS
{
	return readTiff<int16_t>(env, fileName, info, zStart, zEnd, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffFLOAT PFV_TIFF_READ_ARGS
{
	return readTiff<float>(env, fileName, info, zStart, zEnd, dest);
}

// 64-bit images are converted to float: ImageStack has no double support.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffDOUBLE PFV_TIFF_READ_ARGS
{
	return readTiffAsFloat<double>(env, fileName, info, zStart, zEnd, dest);
}

// Signed 8-bit images, shifted into 0-255 (a Java-side calibration maps back).
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffINT8 PFV_TIFF_READ_ARGS
{
	return readTiffShifted<uint8_t, int8_t>(env, fileName, info, zStart, zEnd, (uint8_t)0x80u, dest);
}

// Signed 16-bit images, shifted into 0-65535 (ImageJ's signed-16 convention).
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffINT16 PFV_TIFF_READ_ARGS
{
	return readTiffShifted<uint16_t, int16_t>(env, fileName, info, zStart, zEnd, (uint16_t)0x8000u, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffINT32 PFV_TIFF_READ_ARGS
{
	return readTiffAsFloat<int32_t>(env, fileName, info, zStart, zEnd, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffUINT32 PFV_TIFF_READ_ARGS
{
	return readTiffAsFloat<uint32_t>(env, fileName, info, zStart, zEnd, dest);
}

// ImageJ has no 64-bit integer type; converted to float like doubles are.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffINT64 PFV_TIFF_READ_ARGS
{
	return readTiffAsFloat<int64_t>(env, fileName, info, zStart, zEnd, dest);
}

JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffUINT64 PFV_TIFF_READ_ARGS
{
	return readTiffAsFloat<uint64_t>(env, fileName, info, zStart, zEnd, dest);
}

// 8-bit RGB/RGBA (interleaved or planar), packed into ImageJ int-based RGB
// pixels: one plane per slice.
JNIEXPORT jobjectArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_parallelReadTiffRGB8 PFV_TIFF_READ_ARGS
{
	Request r;
	if (!request(env, fileName, info, zStart, zEnd, 1, r)) return nullptr;
	if (r.info.samples < 3) {
		pfv::throwRuntime(env, "The TIFF is not an RGB image");
		return nullptr;
	}
	const pfv::ThreadCap cap(threadsFor(r.info, zStart, zEnd));
	jobjectArray out = planesFor<int32_t>(env, r, r.slices, dest);
	if (out == nullptr) return nullptr;
	const bool ok = readSlabs(env, r, imageJStack(r), [&](const void* buf, uint64_t s0, uint64_t s1) {
		return rgbSlabToJava(env, r.info, buf, s0, s1, out);
	});
	return ok ? out : nullptr;
}

// For tests: the most bytes of slices a read of an ImageJ stack converts
// through its native buffer at once (at least one slice), and the least a
// slab of a cpp-tiff read holds, then cut into slabs whatever its slices'
// size; 0 or less: the defaults (1 GiB and 4 GiB, see tiffSlabSlices).
JNIEXPORT void JNICALL Java_edu_abc_berkeley_ParallelReadNative_setTiffSlabBytes
	(JNIEnv*, jclass, jlong bytes)
{
	slabOverride = bytes > 0 ? (uint64_t)bytes : 0;
}

// The most threads the native reads of the calling thread use from now on
// (OpenMP keeps it per thread): readers of a folder's files opened at once
// share the cores. 0 or less: every core again.
JNIEXPORT void JNICALL Java_edu_abc_berkeley_ParallelReadNative_limitThreads
	(JNIEnv*, jclass, jint threads)
{
	omp_set_num_threads(threads > 0 ? (int)threads : omp_get_num_procs());
}

// The most threads the native reads of the calling thread use (for tests).
JNIEXPORT jint JNICALL Java_edu_abc_berkeley_ParallelReadNative_threadLimit
	(JNIEnv*, jclass)
{
	return (jint)omp_get_max_threads();
}

// One open and one walk of the file's directories; see the top of this file.
JNIEXPORT jlongArray JNICALL Java_edu_abc_berkeley_ParallelReadNative_getTiffInfo
	(JNIEnv* env, jobject, jstring fileName)
{
	const char* fName = env->GetStringUTFChars(fileName, nullptr);
	if (fName == nullptr) return nullptr;
	TiffInfo info;
	const uint8_t failed = getTiffInfo(fName, info);
	env->ReleaseStringUTFChars(fileName, fName);
	if (failed) {
		pfv::throwRuntime(env, "The file cannot be opened as a TIFF");
		return nullptr;
	}
	jlong v[I_COUNT];
	v[I_WIDTH] = (jlong)info.x;
	v[I_HEIGHT] = (jlong)info.y;
	v[I_SLICES] = (jlong)info.z;
	v[I_DIRECTORIES] = (jlong)info.directories;
	v[I_BITS] = (jlong)info.bits;
	v[I_SAMPLES] = (jlong)info.samples;
	v[I_SAMPLE_FORMAT] = (jlong)info.sampleFormat;
	v[I_STRIP_SIZE] = (jlong)info.stripSize;
	v[I_COMPRESSION] = (jlong)info.compression;
	v[I_PLANAR] = info.planar ? 1 : 0;
	v[I_TILED] = info.tiled ? 1 : 0;
	v[I_IMAGEJ] = info.imageJ ? 1 : 0;
	v[I_IMAGEJ_IMAGES] = (jlong)info.imageJImages;
	jlongArray out = env->NewLongArray(I_COUNT);
	if (out != nullptr) env->SetLongArrayRegion(out, 0, I_COUNT, v);
	return out;
}
