#ifndef RQ_SCISSOR_V1_H
#define RQ_SCISSOR_V1_H
#include <stdint.h>
#include <stddef.h>
#define RQ_SCISSOR_VERSION UINT32_C(1)
#define RQ_SCISSOR_CONTRACT UINT64_C(0x5251534349535331)
#define RQ_SCISSOR_MAX_EXTENT UINT32_C(16777216)
typedef struct rq_scissor_header { uint32_t version,table_size; uint64_t contract,generation; } rq_scissor_header;
typedef struct rq_scissor_pixels { int32_t x,y; uint32_t width,height; } rq_scissor_pixels;
/* Synchronous trusted CPU call. output is caller-owned aligned writable POD,
 * output_size must be 16. No pointer retention, allocation, callbacks or unwind.
 * Extents are 1..2^24; finite normalized rectangle and finite endpoint sums.
 * Success=0 writes one complete result. Any rejection leaves output unchanged.
 * Module images/table/function pointers remain resident until process exit. */
typedef int32_t (*rq_scissor_project)(uint32_t,uint32_t,float,float,float,float,rq_scissor_pixels*,uint32_t);
typedef struct rq_scissor_table { rq_scissor_header header; rq_scissor_project project; } rq_scissor_table;
const rq_scissor_header *rq_scissor_api(void);
_Static_assert(sizeof(rq_scissor_header)==24 && _Alignof(rq_scissor_header)==8,"header layout");
_Static_assert(offsetof(rq_scissor_header,generation)==16,"generation offset");
_Static_assert(sizeof(rq_scissor_pixels)==16 && _Alignof(rq_scissor_pixels)==4,"pixels layout");
_Static_assert(offsetof(rq_scissor_pixels,height)==12,"height offset");
_Static_assert(sizeof(rq_scissor_table)==32 && offsetof(rq_scissor_table,project)==24,"table layout");
#endif
