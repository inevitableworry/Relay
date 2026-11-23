#ifndef RELAY_JXR_ALLOCATION_GUARD_H
#define RELAY_JXR_ALLOCATION_GUARD_H
#include <stddef.h>
#include <stdlib.h>
#include <wchar.h>
#ifdef __cplusplus
extern "C" {
#endif
void* relay_jxr_malloc(size_t size);
void* relay_jxr_calloc(size_t count, size_t size);
void* relay_jxr_realloc(void* pointer, size_t size);
void relay_jxr_free(void* pointer);
void relay_jxr_allocation_begin(void);
void relay_jxr_allocation_end(void);
/* strcodec.c defines this ANSI helper but the original header omits its prototype. */
unsigned int _byteswap_ulong(unsigned int value);
#ifdef __cplusplus
}
#endif
/* Force-included only for the vendored C target, never for our C++ wrappers. */
#ifndef RELAY_JXR_ALLOCATOR_IMPLEMENTATION
#define malloc relay_jxr_malloc
#define calloc relay_jxr_calloc
#define realloc relay_jxr_realloc
#define free relay_jxr_free
#endif
#endif
