// alsread — read the Mac's ambient light sensor in lux.
//
// The ALS is not a published IORegistry property; it is delivered as HID events.
// On this MacBookPro16,1 it appears as AppleUSBALSService on HID usage page 0x20
// (Sensor), usage 0x41 (Ambient Light Sensor). Reading it means going through
// IOHIDEventSystemClient, which is private API — hence the extern declarations.
//
// build: clang -O2 -o alsread alsread.c -framework CoreFoundation -framework IOKit

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <CoreFoundation/CoreFoundation.h>

typedef struct __IOHIDEvent         *IOHIDEventRef;
typedef struct __IOHIDServiceClient *IOHIDServiceClientRef;
typedef struct __IOHIDEventSystemClient *IOHIDEventSystemClientRef;

extern IOHIDEventSystemClientRef IOHIDEventSystemClientCreate(CFAllocatorRef allocator);
extern int        IOHIDEventSystemClientSetMatching(IOHIDEventSystemClientRef, CFDictionaryRef);
extern CFArrayRef IOHIDEventSystemClientCopyServices(IOHIDEventSystemClientRef);
extern IOHIDEventRef IOHIDServiceClientCopyEvent(IOHIDServiceClientRef, int64_t type,
                                                 int32_t options, int64_t timeout);
extern double IOHIDEventGetFloatValue(IOHIDEventRef, int32_t field);

#define kIOHIDEventTypeALS   12
#define kIOHIDEventFieldALS  (kIOHIDEventTypeALS << 16)

static CFDictionaryRef match_dict(int usagePage, int usage)
{
    CFNumberRef pg = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, &usagePage);
    CFNumberRef us = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, &usage);
    CFStringRef keys[]  = { CFSTR("PrimaryUsagePage"), CFSTR("PrimaryUsage") };
    CFTypeRef   vals[]  = { pg, us };
    CFDictionaryRef d = CFDictionaryCreate(kCFAllocatorDefault, (const void **)keys,
        (const void **)vals, 2, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CFRelease(pg); CFRelease(us);
    return d;
}

// Returns lux, or -1 if no sensor answered.
static double read_lux(void)
{
    IOHIDEventSystemClientRef client = IOHIDEventSystemClientCreate(kCFAllocatorDefault);
    if (!client) return -1;

    // 0x20/0x41 is the standard Sensor / Ambient Light usage pair; 0xFF00/0x04
    // is Apple's older vendor pairing. Try both.
    const int pairs[][2] = { {0x20, 0x41}, {0xFF00, 0x04} };
    double lux = -1;

    for (int i = 0; i < 2 && lux < 0; i++) {
        CFDictionaryRef m = match_dict(pairs[i][0], pairs[i][1]);
        IOHIDEventSystemClientSetMatching(client, m);
        CFRelease(m);

        CFArrayRef services = IOHIDEventSystemClientCopyServices(client);
        if (!services) continue;

        for (CFIndex j = 0; j < CFArrayGetCount(services); j++) {
            IOHIDServiceClientRef svc = (IOHIDServiceClientRef)CFArrayGetValueAtIndex(services, j);
            IOHIDEventRef ev = IOHIDServiceClientCopyEvent(svc, kIOHIDEventTypeALS, 0, 0);
            if (!ev) continue;
            double v = IOHIDEventGetFloatValue(ev, kIOHIDEventFieldALS);
            CFRelease(ev);
            if (v >= 0) { lux = v; break; }
        }
        CFRelease(services);
    }
    CFRelease(client);
    return lux;
}

extern CFIndex   IOHIDEventGetIntegerValue(IOHIDEventRef, int32_t field);
extern const uint8_t *IOHIDEventGetDataValue(IOHIDEventRef, int32_t field);

#define kIOHIDEventTypeVendorDefined      1
#define kIOHIDEventFieldVendorDefinedLen  ((kIOHIDEventTypeVendorDefined << 16) | 3)
#define kIOHIDEventFieldVendorDefinedData ((kIOHIDEventTypeVendorDefined << 16) | 4)

// Lid angle in degrees, or -1. HID usage page 0x20 / usage 0x8A, delivered as a
// vendor-defined event whose payload is a little-endian 16-bit angle.
static int read_lid_angle(void)
{
    IOHIDEventSystemClientRef client = IOHIDEventSystemClientCreate(kCFAllocatorDefault);
    if (!client) return -1;

    CFDictionaryRef m = match_dict(0x20, 0x8A);
    IOHIDEventSystemClientSetMatching(client, m);
    CFRelease(m);

    int angle = -1;
    CFArrayRef services = IOHIDEventSystemClientCopyServices(client);
    if (services) {
        for (CFIndex i = 0; i < CFArrayGetCount(services) && angle < 0; i++) {
            IOHIDServiceClientRef svc = (IOHIDServiceClientRef)CFArrayGetValueAtIndex(services, i);
            IOHIDEventRef ev = IOHIDServiceClientCopyEvent(svc, kIOHIDEventTypeVendorDefined, 0, 0);
            if (!ev) continue;
            CFIndex len = IOHIDEventGetIntegerValue(ev, kIOHIDEventFieldVendorDefinedLen);
            const uint8_t *d = IOHIDEventGetDataValue(ev, kIOHIDEventFieldVendorDefinedData);
            if (d && len >= 2) angle = d[0] | (d[1] << 8);
            CFRelease(ev);
        }
        CFRelease(services);
    }
    CFRelease(client);
    return angle;
}

int main(int argc, char **argv)
{
    if (argc > 1 && !strcmp(argv[1], "lid")) {
        int a = read_lid_angle();
        if (a < 0) { fprintf(stderr, "alsread: lid angle sensor did not answer\n"); return 1; }
        printf("%d\n", a);
        return 0;
    }

    int watch = (argc > 1 && !strcmp(argv[1], "watch"));
    double interval = (argc > 2) ? atof(argv[2]) : 2.0;

    do {
        double lux = read_lux();
        if (lux < 0) {
            fprintf(stderr, "alsread: no ambient light sensor responded\n");
            return 1;
        }
        printf("%.1f\n", lux);
        fflush(stdout);
        if (watch) usleep((useconds_t)(interval * 1e6));
    } while (watch);

    return 0;
}
