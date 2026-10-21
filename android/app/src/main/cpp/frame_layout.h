#pragma once
#include <cstdint>

struct RotatedFrameLayout {
    int source_width;
    int source_height;
    int rotation;
    constexpr int width() const { return rotation == 90 || rotation == 270 ? source_height : source_width; }
    constexpr int height() const { return rotation == 90 || rotation == 270 ? source_width : source_height; }
    constexpr int64_t pixelIndex(int x, int y) const {
        const int dx = rotation == 90 ? source_height - 1 - y : rotation == 180 ? source_width - 1 - x : rotation == 270 ? y : x;
        const int dy = rotation == 90 ? x : rotation == 180 ? source_height - 1 - y : rotation == 270 ? source_width - 1 - x : y;
        return static_cast<int64_t>(dy) * width() + dx;
    }
};
