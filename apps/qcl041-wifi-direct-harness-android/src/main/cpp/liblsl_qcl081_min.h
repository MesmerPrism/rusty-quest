#pragma once

#include <stdint.h>

#if defined(_WIN32) || defined(__CYGWIN__)
#define LIBLSL_C_API
#else
#define LIBLSL_C_API __attribute__((visibility("default")))
#endif

typedef struct lsl_streaminfo_struct_ *lsl_streaminfo;
typedef struct lsl_outlet_struct_ *lsl_outlet;
typedef struct lsl_inlet_struct_ *lsl_inlet;

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
LIBLSL_C_API const char *lsl_get_xml(lsl_streaminfo info);
LIBLSL_C_API const char *lsl_get_hostname(lsl_streaminfo info);
LIBLSL_C_API const char *lsl_get_uid(lsl_streaminfo info);
LIBLSL_C_API const char *lsl_get_session_id(lsl_streaminfo info);
LIBLSL_C_API double lsl_get_created_at(lsl_streaminfo info);
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
LIBLSL_C_API int32_t lsl_push_sample_dtp(lsl_outlet out, const double *data, double timestamp, int32_t pushthrough);
LIBLSL_C_API int32_t lsl_resolve_byprop(
    lsl_streaminfo *buffer,
    uint32_t buffer_elements,
    const char *prop,
    const char *value,
    int32_t minimum,
    double timeout
);
LIBLSL_C_API lsl_inlet lsl_create_inlet(
    lsl_streaminfo info,
    int32_t max_buflen,
    int32_t max_chunklen,
    int32_t recover
);
LIBLSL_C_API void lsl_destroy_inlet(lsl_inlet in);
LIBLSL_C_API void lsl_open_stream(lsl_inlet in, double timeout, int32_t *ec);
LIBLSL_C_API double lsl_time_correction(lsl_inlet in, double timeout, int32_t *ec);
LIBLSL_C_API double lsl_pull_sample_d(
    lsl_inlet in,
    double *buffer,
    int32_t buffer_elements,
    double timeout,
    int32_t *ec
);
}
