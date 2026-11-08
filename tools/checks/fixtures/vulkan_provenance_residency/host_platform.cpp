// Android platform leaves only. Production admission and Vulkan wrappers remain
// unchanged.
#include <android/log.h>
#include <fcntl.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>

extern "C" int __android_log_print(int, const char* tag, const char* format,
                                   ...) {
  std::fprintf(stderr, "%s: ", tag);
  va_list values;
  va_start(values, format);
  const int result = std::vfprintf(stderr, format, values);
  va_end(values);
  std::fputc('\n', stderr);
  return result;
}

extern "C" int __system_property_get(const char* name, char* value) {
  if (std::strcmp(name, "debug.rustyquest.vulkan_provenance.enabled") != 0) {
    value[0] = '\0';
    return 0;
  }
  const char* selected = std::getenv("RQVP_HOST_SELECTED");
  const bool enabled = selected && std::strcmp(selected, "1") == 0;
  value[0] = enabled ? '1' : '0';
  value[1] = '\0';
  std::fprintf(stderr, "host-property-read selected=%c\n", value[0]);
  return 1;
}

extern "C" int __real_open(const char*, int, ...);
extern "C" int __wrap_open(const char* path, int flags, ...) {
  mode_t mode = 0;
  const bool creates = (flags & O_CREAT) != 0;
  if (creates) {
    va_list values;
    va_start(values, flags);
    mode = static_cast<mode_t>(va_arg(values, int));
    va_end(values);
  }
  char production_sink[512];
  std::snprintf(
      production_sink, sizeof(production_sink),
      "/data/user/0/runtime.host/files/rusty-quest-vulkan-provenance-%ld.jsonl",
      static_cast<long>(getpid()));
  const char* destination = path;
  if (std::strcmp(path, "/proc/self/cmdline") == 0) {
    const char* supplied = std::getenv("RQVP_HOST_CMDLINE");
    if (supplied) destination = supplied;
  } else if (std::strcmp(path, production_sink) == 0) {
    const char* supplied = std::getenv("RQVP_HOST_SINK");
    if (supplied) destination = supplied;
    std::fprintf(stderr, "host-sink-open flags=%d exclusive=%d mode=%o\n",
                 flags, (flags & O_EXCL) != 0, static_cast<unsigned>(mode));
  }
  // Pass the original flags and mode unchanged, including O_EXCL. No other
  // pathname is redirected, and real Linux read/write/close operations remain.
  return creates ? __real_open(destination, flags, mode)
                 : __real_open(destination, flags);
}
