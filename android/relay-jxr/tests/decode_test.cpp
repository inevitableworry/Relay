#include "relay_jxr_decoder.h"
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "Usage: relay-jxr-test capture.jxr [preview.ppm]\n"); return 2; }
    std::ifstream stream(argv[1], std::ios::binary);
    std::vector<uint8_t> input((std::istreambuf_iterator<char>(stream)), {});
    relay::JxrPreview full, small, invalid;
    if (!relay::decodeJxr(input.data(), input.size(), 2048, full)
            || !relay::decodeJxr(input.data(), input.size(), 128, small)) {
        std::fprintf(stderr, "Real JPEG XR decode failed: full=%s small=%s\n", full.error ? full.error : "OK", small.error ? small.error : "OK"); return 1;
    }
    if (full.width < 1 || full.height < 1 || full.width > 2048 || full.height > 2048
            || small.width > 128 || small.height > 128 || small.rgba.empty()) return 1;
    if (relay::decodeJxr(input.data(), input.size() / 2, 2048, invalid)) {
        std::fprintf(stderr, "Truncated input was not rejected\n"); return 1;
    }
    auto malformed = input;
    malformed[4] = malformed[5] = malformed[6] = malformed[7] = 255;
    if (relay::decodeJxr(malformed.data(), malformed.size(), 2048, invalid)) return 1;
    uint64_t hash = 1469598103934665603ull;
    for (auto value : full.rgba) { hash ^= value; hash *= 1099511628211ull; }
    std::printf("PASS source=%dx%d preview=%dx%d small=%dx%d HDR=%d pixelHash=%016llx; truncated/header bounds rejected\n",
        full.sourceWidth, full.sourceHeight, full.width, full.height, small.width, small.height,
        full.toneMapped, static_cast<unsigned long long>(hash));
    if (argc > 2) {
        std::ofstream output(argv[2], std::ios::binary);
        output << "P6\n" << full.width << ' ' << full.height << "\n255\n";
        for (size_t p = 0; p < full.rgba.size(); p += 4) output.write(reinterpret_cast<const char*>(full.rgba.data() + p), 3);
    }
    return 0;
}
