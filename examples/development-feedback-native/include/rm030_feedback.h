#ifndef RM030_FEEDBACK_H
#define RM030_FEEDBACK_H
#include <stdint.h>

#define RM030_ABI_VERSION UINT32_C(1)
#define RM030_CONTRACT UINT64_C(0x524d303330435055)

typedef struct rm030_header {
    uint32_t abi_version;
    uint32_t table_size;
    uint64_t contract;
    uint64_t generation;
} rm030_header;

/* Synchronous call. All pointers belong to the host and are borrowed only for
 * the call. output must be aligned int64_t; bytes must point to exactly eight
 * writable bytes. They must not overlap. Status 0 means completed, otherwise
 * output/bytes must remain unchanged. No pointer retention or unwinding.
 * The host exclusively owns and increments its completion counter. */
typedef int32_t (*rm030_transform)(int64_t input, int64_t prior,
    int64_t *output, uint8_t *bytes, uint32_t length);

typedef struct rm030_api {
    rm030_header header;
    rm030_transform transform;
} rm030_api;

/* Exported symbol. First inspect only the header. Require exact ABI, local
 * sizeof(rm030_api), contract and expected fresh generation before casting.
 * Table and function lifetime is the process lifetime; unloading unsupported.
 * Contract: A adds input+1 to prior; B adds 2*input+2. bytes encodes output LE.
 * Overflow/non-eight length/null pointers reject without writes. */
#ifdef __cplusplus
extern "C" {
#endif
const rm030_header *rm030_feedback_api(void);
#ifdef __cplusplus
}
#endif
#endif
