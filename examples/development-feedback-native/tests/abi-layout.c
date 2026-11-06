#include <stddef.h>
#include "rm030_feedback.h"

#if !defined(__ANDROID__) || !defined(__aarch64__)
#error "The actual Android AArch64 C target is required"
#endif
_Static_assert(__ANDROID_API__ >= 26, "minimum Android API");

/* Same literal layout assertions as abi.rs: the actual target C compiler and
 * Rust compiler must independently agree before any Android artifact passes. */
_Static_assert(sizeof(void *) == 8, "64-bit target required");
_Static_assert(sizeof(rm030_header) == 24, "header size");
_Static_assert(_Alignof(rm030_header) == 8, "header alignment");
_Static_assert(offsetof(rm030_header, abi_version) == 0, "version offset");
_Static_assert(offsetof(rm030_header, table_size) == 4, "size offset");
_Static_assert(offsetof(rm030_header, contract) == 8, "contract offset");
_Static_assert(offsetof(rm030_header, generation) == 16, "generation offset");
_Static_assert(sizeof(rm030_api) == 32, "table size");
_Static_assert(_Alignof(rm030_api) == 8, "table alignment");
_Static_assert(offsetof(rm030_api, header) == 0, "header offset");
_Static_assert(offsetof(rm030_api, transform) == 24, "function offset");
