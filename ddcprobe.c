// ddcprobe — raw DDC/CI access for Intel Macs (IOFramebuffer I2C path).
//
// ddcctl only exposes 8 hardcoded controls. This talks to arbitrary VCP codes so
// we can ask the monitor what it actually implements.
//
//   ddcprobe caps            fetch + print the DDC/CI capabilities string (VCP 0xF3)
//   ddcprobe get <hex>       read one VCP code
//   ddcprobe set <hex> <n>   write one VCP code
//   ddcprobe scan            read every code 0x00-0xFF, list the ones that answer
//
// build: clang -O2 -o ddcprobe ddcprobe.c -framework IOKit -framework CoreFoundation

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/file.h>
#include <IOKit/IOKitLib.h>
#include <IOKit/i2c/IOI2CInterface.h>

// DDC/CI has no transaction isolation: a reply is just the next read on the
// bus. Two processes probing at once get each other's replies, which surfaces
// as plausible-looking but wrong values rather than an error. Serialise every
// ddcprobe invocation system-wide. Held until the process exits.
#define LOCKFILE "/tmp/monctl-ddc.lock"

static int lock_bus(void)
{
    int fd = open(LOCKFILE, O_CREAT | O_RDWR, 0666);
    if (fd < 0) return -1;
    if (flock(fd, LOCK_EX) != 0) { close(fd); return -1; }
    return fd;
}

#define DDC_ADDR_W   0x6E
#define DDC_ADDR_R   0x6F
#define DDC_HOST_SRC 0x51
#define MS (1000ULL * 1000ULL)

static io_service_t   g_fb        = MACH_PORT_NULL;
static IOOptionBits   g_bus       = 0;
static io_service_t   g_interface = MACH_PORT_NULL;
static IOI2CConnectRef g_connect  = NULL;

// Opening the I2C interface costs ~170 ms — far more than the 40 ms protocol
// delay — so hold it open across a batch instead of per transaction.
static int i2c_open(void)
{
    if (g_connect) return 0;
    if (IOFBCopyI2CInterfaceForBus(g_fb, g_bus, &g_interface) != kIOReturnSuccess)
        return -1;
    if (IOI2CInterfaceOpen(g_interface, kNilOptions, &g_connect) != kIOReturnSuccess) {
        IOObjectRelease(g_interface);
        g_interface = MACH_PORT_NULL;
        return -1;
    }
    return 0;
}

static void i2c_close(void)
{
    if (g_connect)   { IOI2CInterfaceClose(g_connect, kNilOptions); g_connect = NULL; }
    if (g_interface) { IOObjectRelease(g_interface); g_interface = MACH_PORT_NULL; }
}

// Send one I2C transaction on the discovered framebuffer/bus.
static int i2c_xfer(UInt8 *send, IOByteCount sendLen,
                    UInt8 *reply, IOByteCount replyLen, UInt64 delayMs)
{
    if (i2c_open() != 0) return -1;

    IOI2CRequest req;
    bzero(&req, sizeof(req));
    req.commFlags            = 0;
    req.sendAddress          = DDC_ADDR_W;
    req.sendTransactionType  = kIOI2CSimpleTransactionType;
    req.sendBuffer           = (vm_address_t)send;
    req.sendBytes            = (UInt32)sendLen;
    req.minReplyDelay        = delayMs * MS;

    if (reply && replyLen) {
        req.replyAddress         = DDC_ADDR_R;
        req.replyTransactionType = kIOI2CDDCciReplyTransactionType;
        req.replyBuffer          = (vm_address_t)reply;
        req.replyBytes           = (UInt32)replyLen;
    } else {
        req.replyTransactionType = kIOI2CNoTransactionType;
        req.replyBytes           = 0;
    }

    IOReturn err = IOI2CSendRequest(g_connect, kNilOptions, &req);
    if (err != kIOReturnSuccess || req.result != kIOReturnSuccess) return -1;
    return 0;
}

// Read a VCP feature. Returns 0 on success and fills cur/max.
static int vcp_get(UInt8 code, int *cur, int *max)
{
    UInt8 send[5] = { DDC_HOST_SRC, 0x82, 0x01, code, 0 };
    send[4] = DDC_ADDR_W ^ send[0] ^ send[1] ^ send[2] ^ send[3];
    UInt8 reply[11];
    bzero(reply, sizeof(reply));

    if (i2c_xfer(send, sizeof(send), reply, sizeof(reply), 40) != 0) return -1;

    if (getenv("DDCPROBE_DEBUG")) {
        fprintf(stderr, "  raw 0x%02X:", code);
        for (size_t i = 0; i < sizeof(reply); i++) fprintf(stderr, " %02x", reply[i]);
        fprintf(stderr, "\n");
    }

    if (reply[2] != 0x02) return -1;   // not a VCP feature reply
    if (reply[3] != 0x00) return -1;   // result code: 1 = unsupported code
    if (reply[4] != code) return -1;   // answered about a different code

    if (max) *max = (reply[6] << 8) | reply[7];
    if (cur) *cur = (reply[8] << 8) | reply[9];
    return 0;
}

static int vcp_set(UInt8 code, int value)
{
    UInt8 send[7] = { DDC_HOST_SRC, 0x84, 0x03, code,
                      (UInt8)(value >> 8), (UInt8)(value & 0xFF), 0 };
    send[6] = DDC_ADDR_W ^ send[0] ^ send[1] ^ send[2] ^ send[3] ^ send[4] ^ send[5];
    return i2c_xfer(send, sizeof(send), NULL, 0, 50);
}

// Capabilities string arrives in fragments; keep asking at increasing offsets
// until the display returns a reply carrying no payload.
static int caps_fetch(char *out, size_t outsz)
{
    size_t total = 0;
    for (int offset = 0; offset < 8192; ) {
        UInt8 send[6] = { DDC_HOST_SRC, 0x83, 0xF3,
                          (UInt8)(offset >> 8), (UInt8)(offset & 0xFF), 0 };
        send[5] = DDC_ADDR_W ^ send[0] ^ send[1] ^ send[2] ^ send[3] ^ send[4];

        UInt8 reply[64];
        bzero(reply, sizeof(reply));
        if (i2c_xfer(send, sizeof(send), reply, sizeof(reply), 50) != 0) return -1;
        if (reply[2] != 0xE3) return -1;                 // not a caps reply

        int len = (reply[1] & 0x7F);                     // payload incl. opcode+offset
        int payload = len - 3;                           // minus 0xE3 + 2 offset bytes
        if (payload <= 0) break;                         // done

        // Reply framing: [6E][80|len][E3][off_hi][off_lo][data...][checksum]
        // so the data starts at index 5.
        int replyOffset = (reply[3] << 8) | reply[4];
        if (replyOffset != offset) {
            fprintf(stderr, "ddcprobe: offset mismatch (asked %d, got %d)\n",
                    offset, replyOffset);
            return -1;
        }

        UInt8 sum = DDC_ADDR_R;                          // checksum seeds on 0x50 ^ 0x6F
        for (int i = 0; i < len + 2; i++) sum ^= reply[i];
        if (sum != 0 && getenv("DDCPROBE_DEBUG"))
            fprintf(stderr, "  (checksum mismatch at offset %d)\n", offset);

        for (int i = 0; i < payload && total < outsz - 1; i++)
            out[total++] = (char)reply[5 + i];
        offset += payload;
        usleep(50000);
    }
    out[total] = '\0';
    return (int)total;
}

// Try every framebuffer/bus until one answers a brightness read.
static int discover(void)
{
    io_iterator_t iter;
    if (IOServiceGetMatchingServices(kIOMainPortDefault,
            IOServiceMatching("IOFramebuffer"), &iter) != kIOReturnSuccess)
        return -1;

    io_service_t fb;
    while ((fb = IOIteratorNext(iter))) {
        IOItemCount busCount = 0;
        if (IOFBGetI2CInterfaceCount(fb, &busCount) != kIOReturnSuccess || busCount == 0) {
            IOObjectRelease(fb);
            continue;
        }
        for (IOOptionBits bus = 0; bus < busCount; bus++) {
            i2c_close();                     // drop any handle from the previous attempt
            g_fb = fb; g_bus = bus;
            int cur, max;
            if (vcp_get(0x10, &cur, &max) == 0) {
                IOObjectRelease(iter);
                if (getenv("DDCPROBE_DEBUG"))
                    fprintf(stderr, "# using framebuffer 0x%x bus %u\n", fb, bus);
                return 0;
            }
        }
        i2c_close();
        IOObjectRelease(fb);
    }
    IOObjectRelease(iter);
    g_fb = MACH_PORT_NULL;
    return -1;
}

static const char *vcp_name(UInt8 c)
{
    switch (c) {
    case 0x02: return "New control value";
    case 0x04: return "Restore factory defaults";
    case 0x05: return "Restore factory brightness/contrast";
    case 0x06: return "Restore factory geometry";
    case 0x08: return "Restore factory colour";
    case 0x0B: return "Colour temperature increment";
    case 0x0C: return "Colour temperature request";
    case 0x10: return "BRIGHTNESS";
    case 0x12: return "CONTRAST";
    case 0x14: return "Select colour preset";
    case 0x16: return "Video gain: RED";
    case 0x18: return "Video gain: GREEN";
    case 0x1A: return "Video gain: BLUE";
    case 0x1E: return "Auto setup";
    case 0x20: return "Horizontal position";
    case 0x30: return "Vertical position";
    case 0x52: return "Active control";
    case 0x59: return "SATURATION: red";
    case 0x5A: return "SATURATION: yellow";
    case 0x5B: return "SATURATION: green";
    case 0x5C: return "SATURATION: cyan";
    case 0x5D: return "SATURATION: blue";
    case 0x5E: return "SATURATION: magenta";
    case 0x60: return "INPUT SOURCE";
    case 0x62: return "AUDIO SPEAKER VOLUME";
    case 0x6C: return "Video black level: RED";
    case 0x6E: return "Video black level: GREEN";
    case 0x70: return "Video black level: BLUE";
    case 0x72: return "GAMMA";
    case 0x86: return "Display scaling (wide mode)";
    case 0x8D: return "AUDIO MUTE";
    case 0x8F: return "Audio treble";
    case 0x91: return "Audio bass";
    case 0x9B: return "HUE: red";
    case 0x9C: return "HUE: yellow";
    case 0x9D: return "HUE: green";
    case 0x9E: return "HUE: cyan";
    case 0x9F: return "HUE: blue";
    case 0xA0: return "HUE: magenta";
    case 0xAA: return "Screen orientation";
    case 0xAC: return "Horizontal frequency";
    case 0xAE: return "Vertical frequency (0.01 Hz)";
    case 0xB2: return "Flat panel sub-pixel layout";
    case 0xB6: return "Display technology type";
    case 0xC0: return "Display usage time (hours)";
    case 0xC6: return "Application enable key";
    case 0xC8: return "Display controller type";
    case 0xC9: return "Display firmware level";
    case 0xCA: return "OSD / button lock";
    case 0xCC: return "OSD language";
    case 0xD6: return "POWER MODE (DPMS)";
    case 0xDF: return "VCP version";
    case 0xFF: return "manufacturer reserved";
    default:
        if (c >= 0xE0) return "manufacturer-specific";
        return "";
    }
}

// Model name from the EDID's 0xFC descriptor. Needs no bus traffic and no lock —
// it reads the cached EDID out of the IORegistry.
static int print_name(void)
{
    io_iterator_t iter;
    if (IOServiceGetMatchingServices(kIOMainPortDefault,
            IOServiceMatching("AppleDisplay"), &iter) != kIOReturnSuccess)
        return -1;

    int found = -1;
    io_service_t svc;
    while (found != 0 && (svc = IOIteratorNext(iter))) {
        CFMutableDictionaryRef props = NULL;
        if (IORegistryEntryCreateCFProperties(svc, &props, kCFAllocatorDefault, 0)
                == kIOReturnSuccess && props) {
            CFDataRef edid = (CFDataRef)CFDictionaryGetValue(props, CFSTR("IODisplayEDID"));
            if (edid && CFGetTypeID(edid) == CFDataGetTypeID() && CFDataGetLength(edid) >= 128) {
                const UInt8 *e = CFDataGetBytePtr(edid);
                for (int off = 54; off <= 108 && found != 0; off += 18) {
                    if (e[off] || e[off+1] || e[off+2] || e[off+3] != 0xFC) continue;
                    char nm[14] = {0};
                    for (int i = 0; i < 13; i++) {
                        char c = (char)e[off + 5 + i];
                        if (c == '\n' || c == '\0') break;
                        nm[i] = c;
                    }
                    for (int i = (int)strlen(nm) - 1; i >= 0 && nm[i] == ' '; i--) nm[i] = '\0';
                    if (nm[0]) { printf("%s\n", nm); found = 0; }
                }
            }
            CFRelease(props);
        }
        IOObjectRelease(svc);
    }
    IOObjectRelease(iter);
    return found;
}

int main(int argc, char **argv)
{
    // Served straight from the IORegistry — must not take the bus lock.
    if (argc > 1 && !strcmp(argv[1], "name"))
        return print_name() == 0 ? 0 : 1;

    if (lock_bus() < 0)
        fprintf(stderr, "ddcprobe: warning — could not take bus lock %s\n", LOCKFILE);

    if (discover() != 0) {
        fprintf(stderr, "ddcprobe: no DDC-capable framebuffer/bus responded\n");
        return 1;
    }
    const char *cmd = (argc > 1) ? argv[1] : "caps";

    if (!strcmp(cmd, "caps")) {
        char buf[8192];
        int n = caps_fetch(buf, sizeof(buf));
        if (n < 0) { fprintf(stderr, "ddcprobe: capabilities request failed\n"); return 1; }
        printf("%s\n", buf);
        return 0;
    }

    if (!strcmp(cmd, "get") && argc > 2) {
        UInt8 code = (UInt8)strtol(argv[2], NULL, 16);
        int cur, max;
        if (vcp_get(code, &cur, &max) != 0) { printf("0x%02X unsupported\n", code); return 1; }
        printf("0x%02X current=%d max=%d  %s\n", code, cur, max, vcp_name(code));
        return 0;
    }

    if (!strcmp(cmd, "set") && argc > 3) {
        UInt8 code = (UInt8)strtol(argv[2], NULL, 16);
        int val = atoi(argv[3]);
        if (vcp_set(code, val) != 0) { fprintf(stderr, "write failed\n"); return 1; }
        printf("0x%02X <- %d\n", code, val);
        return 0;
    }

    // Batch read: one discovery, many codes. "code cur max" per line.
    // The web UI needs ~30 values at once; separate invocations would pay the
    // framebuffer discovery cost every time.
    if (!strcmp(cmd, "many")) {
        for (int i = 2; i < argc; i++) {
            UInt8 code = (UInt8)strtol(argv[i], NULL, 16);
            int cur, max;
            if (vcp_get(code, &cur, &max) == 0)
                printf("%02X %d %d\n", code, cur, max);
            else
                printf("%02X - -\n", code);
            // Flush per line so a consumer can stream results as they land
            // instead of waiting for the whole batch.
            fflush(stdout);
        }
        return 0;
    }

    if (!strcmp(cmd, "scan")) {
        printf("code  current    max  name\n");
        printf("----  -------  -----  ----------------------------------\n");
        int found = 0;
        for (int c = 0; c <= 0xFF; c++) {
            int cur, max;
            if (vcp_get((UInt8)c, &cur, &max) == 0) {
                printf("0x%02X  %7d  %5d  %s\n", c, cur, max, vcp_name((UInt8)c));
                found++;
            }
            usleep(30000);
        }
        printf("\n%d VCP codes answered\n", found);
        return 0;
    }

    fprintf(stderr, "usage: ddcprobe caps | get <hex> | set <hex> <n> | scan\n");
    return 2;
}
