#pragma once

#include <stdint.h>

#if defined(_WIN32) || defined(__CYGWIN__)
#define LIBLSL_C_API
#else
#define LIBLSL_C_API __attribute__((visibility("default")))
#endif

typedef struct lsl_streaminfo_struct_ *lsl_streaminfo;
typedef struct lsl_outlet_struct_ *lsl_outlet;

typedef enum {
    cft_undefined = 0,
    cft_float32 = 1,
    cft_double64 = 2,
    cft_string = 3,
    cft_int32 = 4,
    cft_int16 = 5,
    cft_int8 = 6,
    cft_int64 = 7
} lsl_channel_format_t;

extern "C" {
LIBLSL_C_API const char *lsl_last_error(void);
LIBLSL_C_API const char *lsl_library_info(void);
LIBLSL_C_API double lsl_local_clock(void);
LIBLSL_C_API lsl_streaminfo lsl_create_streaminfo(
    const char *name,
    const char *type,
    int32_t channel_count,
    double nominal_srate,
    lsl_channel_format_t channel_format,
    const char *source_id
);
LIBLSL_C_API void lsl_destroy_streaminfo(lsl_streaminfo info);
LIBLSL_C_API lsl_outlet lsl_create_outlet(lsl_streaminfo info, int32_t chunk_size, int32_t max_buffered);
LIBLSL_C_API void lsl_destroy_outlet(lsl_outlet out);
LIBLSL_C_API int32_t lsl_push_sample_ftp(lsl_outlet out, const float *data, double timestamp, int32_t pushthrough);
}
