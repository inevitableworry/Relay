#include "relay_jxr_decoder.h"
#define RELAY_JXR_ALLOCATOR_IMPLEMENTATION
#include "allocation_guard.h"
extern "C" {
#include "JXRGlue.h"
}
#undef min
#undef max
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <new>

namespace {
constexpr size_t kMaxInput = 32u * 1024u * 1024u;
constexpr size_t kMaxSourcePixels = 32u * 1024u * 1024u;
constexpr size_t kMaxPreviewPixels = 2048u * 2048u;
constexpr size_t kMaxDimension = 8192;
constexpr size_t kReadAhead = 8192;
std::mutex decodeMutex;

uint16_t le16(const uint8_t* p) { return uint16_t(p[0]) | uint16_t(p[1]) << 8; }
uint32_t le32(const uint8_t* p) {
    return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
}
bool range(size_t offset, size_t count, size_t size) {
    return offset <= size && count <= size - offset;
}
bool dimensions(size_t width, size_t height) {
    return width && height && width <= kMaxDimension && height <= kMaxDimension
        && width <= kMaxSourcePixels / height;
}

// Validate the container before the legacy library parses any metadata or allocates.
// The Microsoft container is little-endian, including on a big-endian host.
bool validContainer(const uint8_t* data, size_t size) {
    if (!data || size < 16 || size > kMaxInput || data[0] != 'I' || data[1] != 'I'
            || data[2] != 0xbc || data[3] > 1) return false;
    const size_t ifd = le32(data + 4);
    if (!range(ifd, 2, size)) return false;
    const size_t entries = le16(data + ifd);
    if (!entries || entries > 256 || !range(ifd + 2, entries * 12 + 4, size)) return false;
    uint32_t width = 0, height = 0, imageOffset = 0, imageSize = 0;
    uint32_t alphaOffset = 0, alphaSize = 0;
    static const size_t typeBytes[] = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8};
    for (size_t index = 0; index < entries; ++index) {
        const uint8_t* entry = data + ifd + 2 + index * 12;
        const uint16_t tag = le16(entry), type = le16(entry + 2);
        const uint32_t count = le32(entry + 4), value = le32(entry + 8);
        if (type == 0 || type >= sizeof(typeBytes) / sizeof(typeBytes[0])) return false;
        if (count > kMaxInput / typeBytes[type]) return false;
        const size_t byteCount = size_t(count) * typeBytes[type];
        if (byteCount > 4 && !range(value, byteCount, size)) return false;
        if (tag == 0xbc80 || tag == 0xbc81 || (tag >= 0xbcc0 && tag <= 0xbcc3)) {
            if (count != 1 || (type != 3 && type != 4)) return false;
            const uint32_t number = type == 3 ? value & 0xffff : value;
            switch (tag) {
                case 0xbc80: width = number; break;
                case 0xbc81: height = number; break;
                case 0xbcc0: imageOffset = number; break;
                case 0xbcc1: imageSize = number; break;
                case 0xbcc2: alphaOffset = number; break;
                case 0xbcc3: alphaSize = number; break;
            }
        }
    }
    // Microsoft's reference encoder writes the absolute end offset in AlphaByteCount
    // (JXRGlueJxr.c WriteContainerPost), as observed in PS5 Media Gallery files.
    // Accept that EOF form while keeping all alpha reads inside the file.
    const bool alphaInFile = !alphaSize || range(alphaOffset, alphaSize, size)
        || (alphaOffset < size && alphaSize == size);
    return dimensions(width, height) && imageSize >= 16 && range(imageOffset, imageSize, size)
        && alphaInFile;
}

struct BoundedInput {
    WMPStream stream{};
    const uint8_t* data;
    size_t size;
    size_t position = 0;
    bool failed = false;
    BoundedInput(const uint8_t* bytes, size_t length) : data(bytes), size(length) {
        stream.state.pvObj = this;
        stream.Close = [](WMPStream**) -> ERR { return WMP_errSuccess; };
        stream.EOS = [](WMPStream* s) -> Bool {
            auto& input = *static_cast<BoundedInput*>(s->state.pvObj);
            return input.position >= input.size;
        };
        stream.Read = [](WMPStream* s, void* buffer, size_t count) -> ERR {
            auto& input = *static_cast<BoundedInput*>(s->state.pvObj);
            if (count > kMaxInput || input.position > input.size + kReadAhead
                    || count > input.size + kReadAhead - input.position) {
                input.failed = true;
                if (count <= kMaxInput) std::memset(buffer, 0, count);
                return WMP_errFileIO;
            }
            const size_t remaining = input.position < input.size ? input.size - input.position : 0;
            const size_t copied = std::min(count, remaining);
            if (copied) std::memcpy(buffer, input.data + input.position, copied);
            // jxrlib reads fixed-size bitstream packets past EOF. Pad those packets;
            // all actual container/stream spans were checked above.
            if (copied < count) std::memset(static_cast<uint8_t*>(buffer) + copied, 0, count - copied);
            input.position += count;
            return WMP_errSuccess;
        };
        stream.Write = [](WMPStream*, const void*, size_t) -> ERR { return WMP_errFileIO; };
        stream.SetPos = [](WMPStream* s, size_t offset) -> ERR {
            auto& input = *static_cast<BoundedInput*>(s->state.pvObj);
            if (offset > input.size + kReadAhead) { input.failed = true; return WMP_errFileIO; }
            input.position = offset;
            return WMP_errSuccess;
        };
        stream.GetPos = [](WMPStream* s, size_t* offset) -> ERR {
            *offset = static_cast<BoundedInput*>(s->state.pvObj)->position;
            return WMP_errSuccess;
        };
    }
};
struct AllocationScope {
    AllocationScope() { relay_jxr_allocation_begin(); }
    ~AllocationScope() { relay_jxr_allocation_end(); }
};
struct DecoderDeleter {
    void operator()(PKImageDecode* decoder) const {
        if (decoder) decoder->Release(&decoder);
    }
};
template<class T> T sample(const uint8_t* pointer) {
    T value;
    std::memcpy(&value, pointer, sizeof(value));
    return value;
}
float half(uint16_t value) {
    const int sign = value & 0x8000 ? -1 : 1;
    const int exponent = (value >> 10) & 31;
    const int mantissa = value & 1023;
    if (exponent == 31) return mantissa ? 0.f : sign * 65504.f;
    if (!exponent) return sign * std::ldexp(float(mantissa), -24);
    return sign * std::ldexp(float(mantissa + 1024), exponent - 25);
}
float channel(const uint8_t* p, BITDEPTH_BITS depth) {
    switch (depth) {
        case BD_8: return *p / 255.f;
        case BD_16: return sample<uint16_t>(p) / 65535.f;
        case BD_16S: return sample<int16_t>(p) / 8192.f;
        case BD_32S: return sample<int32_t>(p) / 16777216.f;
        case BD_16F: return half(sample<uint16_t>(p));
        case BD_32F: return sample<float>(p);
        default: return 0.f;
    }
}
float positive(float value) {
    return std::isfinite(value) ? std::max(0.f, std::min(65504.f, value)) : 0.f;
}
float unit(float value) { return std::max(0.f, std::min(1.f, positive(value))); }
uint8_t byte(float value) { return uint8_t(unit(value) * 255.f + 0.5f); }
float srgb(float value) {
    value = unit(value);
    return value <= 0.0031308f ? value * 12.92f : 1.055f * std::pow(value, 1.f / 2.4f) - 0.055f;
}
struct Pixel { float r, g, b, a; };
Pixel readPixel(const uint8_t* row, size_t x, const PKPixelInfo& info) {
    if (info.bdBitDepth == BD_1 || info.bdBitDepth == BD_1alt) {
        const float value = ((row[x / 8] >> (7 - x % 8)) & 1) ? 1.f : 0.f;
        return {value, value, value, 1.f};
    }
    const uint8_t* p = row + x * (info.cbitUnit / 8);
    if (info.bdBitDepth == BD_5 || info.bdBitDepth == BD_565) {
        const uint16_t packed = sample<uint16_t>(p);
        const unsigned greenBits = info.bdBitDepth == BD_565 ? 6 : 5;
        return {float((packed >> (5 + greenBits)) & 31) / 31.f,
            float((packed >> 5) & ((1u << greenBits) - 1)) / float((1u << greenBits) - 1),
            float(packed & 31) / 31.f, 1.f};
    }
    if (info.bdBitDepth == BD_10) {
        const uint32_t packed = sample<uint32_t>(p);
        return {float((packed >> 20) & 1023) / 1023.f, float((packed >> 10) & 1023) / 1023.f,
            float(packed & 1023) / 1023.f, 1.f};
    }
    const size_t bytesPerChannel = (info.bdBitDepth == BD_8 ? 1 :
        (info.bdBitDepth == BD_32F || info.bdBitDepth == BD_32S ? 4 : 2));
    const float first = positive(channel(p, info.bdBitDepth));
    if (info.cfColorFormat == Y_ONLY) return {first, first, first, 1.f};
    Pixel result{first, positive(channel(p + bytesPerChannel, info.bdBitDepth)),
        positive(channel(p + 2 * bytesPerChannel, info.bdBitDepth)), 1.f};
    if (info.grBit & PK_pixfmtBGR) std::swap(result.r, result.b);
    if (info.grBit & PK_pixfmtHasAlpha) result.a = unit(channel(p + 3 * bytesPerChannel, info.bdBitDepth));
    if ((info.grBit & PK_pixfmtPreMul) && result.a > 0.f) {
        result.r /= result.a; result.g /= result.a; result.b /= result.a;
    }
    return result;
}
bool supported(const PKPixelInfo& info) {
    if ((info.cfColorFormat != CF_RGB && info.cfColorFormat != Y_ONLY)
            || info.cChannel > 4 || !info.cChannel || !info.cbitUnit || info.cbitUnit > 128) return false;
    switch (info.bdBitDepth) {
        case BD_1: case BD_1alt: case BD_8: case BD_16: case BD_16S: case BD_32S:
        case BD_16F: case BD_32F: case BD_5: case BD_565: case BD_10: return true;
        default: return false;
    }
}
}

bool relay::decodeJxr(const uint8_t* bytes, size_t size, int maxEdge, JxrPreview& output) {
    output = {};
    output.error = "Invalid or oversized JPEG XR container";
    if (maxEdge < 1 || !validContainer(bytes, size)) return false;
    maxEdge = std::min(maxEdge, 2048);
    std::lock_guard<std::mutex> lock(decodeMutex);
    AllocationScope allocations;
    try {
        BoundedInput input(bytes, size);
        PKImageDecode* raw = nullptr;
        output.error = "JPEG XR decoder allocation failed";
        if (PKImageDecode_Create_WMP(&raw) != WMP_errSuccess || !raw) return false;
        std::unique_ptr<PKImageDecode, DecoderDeleter> decoder(raw);
        output.error = "JPEG XR header could not be decoded";
        if (raw->Initialize(raw, &input.stream) != WMP_errSuccess || input.failed) return false;
        auto& image = raw->WMP.wmiI;
        output.error = "JPEG XR dimensions exceed preview limits";
        if (!dimensions(image.cWidth, image.cHeight)) return false;
        PKPixelInfo info{};
        info.pGUIDPixFmt = &raw->guidPixFormat;
        output.error = "JPEG XR pixel format is unsupported";
        if (PixelFormatLookup(&info, LOOKUP_FORWARD) != WMP_errSuccess || !supported(info)) return false;
        const size_t width = image.cWidth, height = image.cHeight;
        size_t divisor = 1;
        while (divisor < 16 && (std::max(width, height) + divisor - 1) / divisor > size_t(maxEdge)) divisor *= 2;
        const size_t scaledWidth = (width + divisor - 1) / divisor;
        const size_t scaledHeight = (height + divisor - 1) / divisor;
        output.error = "JPEG XR thumbnail dimensions exceed preview limits";
        if (scaledWidth > 2048 || scaledHeight > 2048 || scaledWidth > kMaxPreviewPixels / scaledHeight) return false;
        image.cfColorFormat = info.cfColorFormat;
        image.bdBitDepth = info.bdBitDepth;
        image.cBitsPerUnit = info.cbitUnit;
        image.bRGB = !(info.grBit & PK_pixfmtBGR);
        image.cThumbnailWidth = scaledWidth;
        image.cThumbnailHeight = scaledHeight;
        image.bSkipFlexbits = FALSE;
        image.cROILeftX = image.cROITopY = 0;
        image.cROIWidth = scaledWidth;
        image.cROIHeight = scaledHeight;
        raw->WMP.wmiSCP.uAlphaMode = info.grBit & PK_pixfmtHasAlpha ? 2 : 0;
        const bool rotated = image.oOrientation >= O_RCW;
        const size_t decodedWidth = rotated ? scaledHeight : scaledWidth;
        const size_t decodedHeight = rotated ? scaledWidth : scaledHeight;
        const size_t stride = ((decodedWidth * info.cbitUnit + 7) / 8 + 15) & ~size_t(15);
        if (stride > 2048u * 16u || decodedHeight > kMaxPreviewPixels * 16u / stride) return false;
        // Native thumbnail decode writes this bounded native-format buffer, never a full-resolution frame.
        std::vector<uint8_t> pixels(stride * decodedHeight, 0);
        PKRect rectangle{0, 0, static_cast<I32>(decodedWidth), static_cast<I32>(decodedHeight)};
        output.error = "JPEG XR pixel decode failed";
        if (raw->Copy(raw, &rectangle, pixels.data(), static_cast<U32>(stride)) != WMP_errSuccess || input.failed) return false;

        const bool linear = info.bdBitDepth == BD_16F || info.bdBitDepth == BD_32F
            || info.bdBitDepth == BD_16S || info.bdBitDepth == BD_32S;
        float white = 1.f;
        if (linear) {
            for (size_t y = 0; y < decodedHeight; ++y) {
                const uint8_t* row = pixels.data() + stride * y;
                for (size_t x = 0; x < decodedWidth; ++x) {
                    const Pixel p = readPixel(row, x, info);
                    white = std::max(white, .2126f * p.r + .7152f * p.g + .0722f * p.b);
                }
            }
        }
        const bool hdr = linear && white > 1.01f;
        const float scale = std::min(1.f, float(maxEdge) / float(std::max(decodedWidth, decodedHeight)));
        const size_t outputWidth = std::max<size_t>(1, size_t(decodedWidth * scale + .5f));
        const size_t outputHeight = std::max<size_t>(1, size_t(decodedHeight * scale + .5f));
        output.rgba.resize(outputWidth * outputHeight * 4);
        for (size_t y = 0; y < outputHeight; ++y) {
            const size_t sourceY = std::min(decodedHeight - 1, y * decodedHeight / outputHeight);
            const uint8_t* row = pixels.data() + stride * sourceY;
            for (size_t x = 0; x < outputWidth; ++x) {
                Pixel p = readPixel(row, std::min(decodedWidth - 1, x * decodedWidth / outputWidth), info);
                if (hdr) {
                    // Extended Reinhard luminance mapping preserves hue and rolls off HDR highlights.
                    // scRGB is linear; this is an SDR gallery preview, not an HDR export.
                    const float luminance = .2126f * p.r + .7152f * p.g + .0722f * p.b;
                    const float mapping = (1.f + luminance / (white * white)) / (1.f + luminance);
                    p.r *= mapping; p.g *= mapping; p.b *= mapping;
                }
                if (linear) { p.r = srgb(p.r); p.g = srgb(p.g); p.b = srgb(p.b); }
                uint8_t* destination = output.rgba.data() + (y * outputWidth + x) * 4;
                destination[0] = byte(p.r * p.a);
                destination[1] = byte(p.g * p.a);
                destination[2] = byte(p.b * p.a);
                destination[3] = byte(p.a);
            }
        }
        output.width = int(outputWidth);
        output.height = int(outputHeight);
        output.sourceWidth = int(rotated ? height : width);
        output.sourceHeight = int(rotated ? width : height);
        output.toneMapped = hdr;
        output.error = nullptr;
        return true;
    } catch (const std::bad_alloc&) {
        output = {};
        output.error = "Insufficient memory for JPEG XR preview";
        return false;
    }
}
