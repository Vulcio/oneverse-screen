#include "../../main/cpp/frame_layout.h"

constexpr RotatedFrameLayout upright{2, 3, 0};
constexpr RotatedFrameLayout clockwise{2, 3, 90};
constexpr RotatedFrameLayout upside_down{2, 3, 180};
constexpr RotatedFrameLayout counter_clockwise{2, 3, 270};
static_assert(upright.width() == 2 && upright.height() == 3);
static_assert(clockwise.width() == 3 && clockwise.height() == 2);
static_assert(upside_down.width() == 2 && upside_down.height() == 3);
static_assert(counter_clockwise.width() == 3 && counter_clockwise.height() == 2);
static_assert(upright.pixelIndex(0, 0) == 0 && upright.pixelIndex(1, 2) == 5);
static_assert(clockwise.pixelIndex(0, 0) == 2 && clockwise.pixelIndex(1, 0) == 5);
static_assert(clockwise.pixelIndex(0, 1) == 1 && clockwise.pixelIndex(1, 1) == 4);
static_assert(clockwise.pixelIndex(0, 2) == 0 && clockwise.pixelIndex(1, 2) == 3);
static_assert(upside_down.pixelIndex(0, 0) == 5 && upside_down.pixelIndex(1, 2) == 0);
static_assert(counter_clockwise.pixelIndex(0, 0) == 3 && counter_clockwise.pixelIndex(1, 0) == 0);
static_assert(counter_clockwise.pixelIndex(0, 2) == 5 && counter_clockwise.pixelIndex(1, 2) == 2);

constexpr bool staysInBounds() {
    for (int rotation = 0; rotation < 360; rotation += 90) {
        const RotatedFrameLayout layout{1280, 720, rotation};
        for (int y = 0; y < 720; ++y) {
            // Check both edges of every scan line without exhausting constexpr compiler limits.
            if (layout.pixelIndex(0, y) < 0 || layout.pixelIndex(1279, y) >= 1280 * 720) return false;
            if (layout.pixelIndex(1279, y) < 0 || layout.pixelIndex(0, y) >= 1280 * 720) return false;
        }
    }
    return true;
}
static_assert(staysInBounds());
