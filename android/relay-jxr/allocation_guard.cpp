#define RELAY_JXR_ALLOCATOR_IMPLEMENTATION
#include "allocation_guard.h"
#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <limits>

namespace {
constexpr size_t kCodecBudget = 48u * 1024u * 1024u;
struct alignas(std::max_align_t) Block {
    Block* previous;
    Block* next;
    size_t size;
};
thread_local Block* head = nullptr;
thread_local size_t used = 0;
}
extern "C" void relay_jxr_allocation_begin() { relay_jxr_allocation_end(); }
extern "C" void relay_jxr_allocation_end() {
    while (head) {
        Block* next = head->next;
        std::free(head);
        head = next;
    }
    used = 0;
}
extern "C" void* relay_jxr_malloc(size_t size) {
    if (size == 0) size = 1;
    if (size > kCodecBudget - sizeof(Block) || used > kCodecBudget - sizeof(Block) - size) return nullptr;
    auto* block = static_cast<Block*>(std::malloc(sizeof(Block) + size));
    if (!block) return nullptr;
    block->previous = nullptr;
    block->next = head;
    block->size = size;
    if (head) head->previous = block;
    head = block;
    used += sizeof(Block) + size;
    return block + 1;
}
extern "C" void* relay_jxr_calloc(size_t count, size_t size) {
    if (count && size > std::numeric_limits<size_t>::max() / count) return nullptr;
    const size_t total = count * size;
    void* result = relay_jxr_malloc(total);
    if (result) std::memset(result, 0, total);
    return result;
}
extern "C" void relay_jxr_free(void* pointer) {
    if (!pointer) return;
    auto* block = static_cast<Block*>(pointer) - 1;
    if (block->previous) block->previous->next = block->next;
    else head = block->next;
    if (block->next) block->next->previous = block->previous;
    used -= sizeof(Block) + block->size;
    std::free(block);
}
extern "C" void* relay_jxr_realloc(void* pointer, size_t size) {
    if (!pointer) return relay_jxr_malloc(size);
    if (!size) { relay_jxr_free(pointer); return nullptr; }
    const size_t oldSize = (static_cast<Block*>(pointer) - 1)->size;
    void* result = relay_jxr_malloc(size);
    if (result) {
        std::memcpy(result, pointer, oldSize < size ? oldSize : size);
        relay_jxr_free(pointer);
    }
    return result;
}
