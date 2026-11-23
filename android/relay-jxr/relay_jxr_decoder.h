#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace relay {
struct JxrPreview {
    int width = 0;
    int height = 0;
    int sourceWidth = 0;
    int sourceHeight = 0;
    bool toneMapped = false;
    const char* error = "No decoded image";
    // Premultiplied RGBA bytes, matching ANDROID_BITMAP_FORMAT_RGBA_8888.
    std::vector<std::uint8_t> rgba;
};
bool decodeJxr(const std::uint8_t* bytes, size_t size, int maxEdge, JxrPreview& output);
}
