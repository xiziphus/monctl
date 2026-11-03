// dimmer — continuous software dimming for external displays, via the gamma
// transfer function.
//
// DDC brightness (VCP 0x10) is an integer 0-100; there is nothing between two
// steps. This scales the display's gamma ramp instead, which is continuous and
// costs no bus traffic at all. Use it as the fine adjust *on top of* whatever
// DDC step the backlight is on.
//
//   dimmer            print the current level (0.10 - 1.00)
//   dimmer 0.83       set it
//   dimmer reset      back to 1.00
//
// Caveat: the ramp is an 8-bit-per-channel LUT (256 entries here), so heavy
// dimming costs tonal resolution. Small trims are visually free; 0.3 will band.
// macOS also resets gamma on display sleep/reconfigure, so it must be reapplied.
//
// build: clang -O2 -o dimmer dimmer.c -framework CoreGraphics -framework CoreFoundation

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <CoreGraphics/CoreGraphics.h>

#define FLOOR 0.10f

static int apply(float level)
{
    if (level < FLOOR) level = FLOOR;
    if (level > 1.0f)  level = 1.0f;

    uint32_t n = 0;
    CGGetOnlineDisplayList(16, NULL, &n);
    CGDirectDisplayID ids[16];
    CGGetOnlineDisplayList(n, ids, &n);

    int touched = 0;
    for (uint32_t i = 0; i < n; i++) {
        if (CGDisplayIsBuiltin(ids[i])) continue;      // external only
        CGError e = CGSetDisplayTransferByFormula(ids[i],
            0.0f, level, 1.0f,
            0.0f, level, 1.0f,
            0.0f, level, 1.0f);
        if (e == kCGErrorSuccess) touched++;
    }
    if (!touched) { fprintf(stderr, "dimmer: no external display\n"); return 1; }
    printf("%.3f\n", level);
    return 0;
}

static int show(void)
{
    uint32_t n = 0;
    CGGetOnlineDisplayList(16, NULL, &n);
    CGDirectDisplayID ids[16];
    CGGetOnlineDisplayList(n, ids, &n);

    for (uint32_t i = 0; i < n; i++) {
        if (CGDisplayIsBuiltin(ids[i])) continue;
        CGGammaValue rmin, rmax, rg, gmin, gmax, gg, bmin, bmax, bg;
        if (CGGetDisplayTransferByFormula(ids[i], &rmin, &rmax, &rg,
                                          &gmin, &gmax, &gg,
                                          &bmin, &bmax, &bg) == kCGErrorSuccess) {
            printf("%.3f\n", rmax);
            return 0;
        }
    }
    fprintf(stderr, "dimmer: no external display\n");
    return 1;
}

int main(int argc, char **argv)
{
    if (argc < 2) return show();
    if (!strcmp(argv[1], "reset")) return apply(1.0f);
    char *end = NULL;
    float v = strtof(argv[1], &end);
    if (end == argv[1]) { fprintf(stderr, "usage: dimmer [0.10-1.00 | reset]\n"); return 2; }
    return apply(v);
}
