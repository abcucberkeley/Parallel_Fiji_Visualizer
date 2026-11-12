#ifndef PFV_RAW_FILE_H
#define PFV_RAW_FILE_H

// Positioned reads of a file's bytes, for pixels stored uncompressed that are
// read straight to where they go. Every thread of a read opens its own
// RawFile, so each is a sequential stream with its own read-ahead state in
// the kernel (one handle shared by all threads defeats the read-ahead).
// POSIX reads with pread; Windows with a FILE* per handle, positioned with
// _fseeki64 before each fread. The path goes to open or fopen as the caller
// has it: the one pfv::nativePath (jni_marshal.h) gives cpp-tiff for fopen
// and TIFFOpen, so both open the same file (on Windows that path is in the
// ANSI code page, or an 8.3 short path).
//
// Header-only and free of upstream headers, like jni_marshal.h.

#include <cstdint>
#include <cstdio>

#ifndef _WIN32
#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#endif

namespace pfv {

class RawFile {
#ifdef _WIN32
	FILE* fp;
#else
	int fd;
	static_assert(sizeof(off_t) >= 8, "file offsets must be 64-bit");
#endif

public:
	explicit RawFile(const char* path) {
#ifdef _WIN32
		fp = fopen(path, "rb");
		// Unbuffered: every read lands straight in its destination
		if (fp != nullptr) setvbuf(fp, nullptr, _IONBF, 0);
#else
		do fd = open(path, O_RDONLY | O_CLOEXEC);
		while (fd < 0 && errno == EINTR);
#endif
	}

	~RawFile() {
#ifdef _WIN32
		if (fp != nullptr) fclose(fp);
#else
		if (fd >= 0) close(fd);
#endif
	}

	RawFile(const RawFile&) = delete;
	RawFile& operator=(const RawFile&) = delete;

	bool isOpen() const {
#ifdef _WIN32
		return fp != nullptr;
#else
		return fd >= 0;
#endif
	}

	// The file's length in bytes, or -1 if it cannot be told
	int64_t size() {
#ifdef _WIN32
		if (fp == nullptr || _fseeki64(fp, 0, SEEK_END) != 0) return -1;
		return (int64_t)_ftelli64(fp);
#else
		struct stat st;
		if (fd < 0 || fstat(fd, &st) != 0) return -1;
		return (int64_t)st.st_size;
#endif
	}

	// Reads the n bytes at offset into dst: false on an error, or when the
	// file ends before them
	bool readAt(void* dst, uint64_t n, uint64_t offset) {
		if (!isOpen()) return false;
		// At most 1 GiB per call: macOS's pread refuses more than INT_MAX bytes
		const uint64_t most = (uint64_t)1 << 30;
		char* d = (char*)dst;
#ifdef _WIN32
		if (_fseeki64(fp, (long long)offset, SEEK_SET) != 0) return false;
		while (n > 0) {
			const size_t got = fread(d, 1, (size_t)(n < most ? n : most), fp);
			if (got == 0) return false;
			d += got;
			n -= got;
		}
#else
		while (n > 0) {
			const ssize_t got = pread(fd, d, (size_t)(n < most ? n : most), (off_t)offset);
			if (got < 0 && errno == EINTR) continue;
			if (got <= 0) return false;
			d += got;
			n -= (uint64_t)got;
			offset += (uint64_t)got;
		}
#endif
		return true;
	}
};

} // namespace pfv

#endif
