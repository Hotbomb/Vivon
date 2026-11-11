/*
 * couchmouse: a virtual mouse for Couch Phone.
 *
 * The app runs this once as root (through Magisk's su). It makes a mouse with Linux uinput, so
 * Android and Samsung DeX see a real USB-style mouse: the pointer shows on the TV, and clicks,
 * right clicks and the scroll wheel work everywhere, exactly as with a plugged-in mouse.
 *
 * It reads one command per line on stdin:
 *   m <dx> <dy>          move the pointer by dx, dy
 *   w <v> <h>            scroll: v up (+) or down (-), h right (+) or left (-), in wheel clicks
 *   b <l|r|m> <1|0>      press (1) or release (0) a button
 *   c <l|r|m>            click a button (press and release)
 *   p                    answers "pong"
 * When stdin closes, the mouse goes away.
 *
 * Original code for couch-phone (MIT). Build: an Android NDK clang, see .github/workflows/build.yml.
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int fd = -1;

static void emit(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof ev) < 0) { /* a full queue drops one event; nothing else to do */ }
}

static void syn(void) { emit(EV_SYN, SYN_REPORT, 0); }

static int button(const char *b) {
    if (!b) return -1;
    switch (b[0]) {
        case 'l': return BTN_LEFT;
        case 'r': return BTN_RIGHT;
        case 'm': return BTN_MIDDLE;
        default: return -1;
    }
}

static int clamp(long v, int lim) { return v > lim ? lim : v < -lim ? -lim : (int) v; }

static int open_uinput(void) {
    const char *paths[] = { "/dev/uinput", "/dev/input/uinput", "/dev/misc/uinput" };
    for (unsigned i = 0; i < sizeof paths / sizeof paths[0]; i++) {
        int f = open(paths[i], O_WRONLY | O_NONBLOCK | O_CLOEXEC);
        if (f >= 0) return f;
    }
    return -1;
}

int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    fd = open_uinput();
    if (fd < 0) { printf("fail cannot open uinput: %s\n", strerror(errno)); return 1; }

    int ok = ioctl(fd, UI_SET_EVBIT, EV_KEY) >= 0
        && ioctl(fd, UI_SET_KEYBIT, BTN_LEFT) >= 0
        && ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT) >= 0
        && ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE) >= 0
        && ioctl(fd, UI_SET_EVBIT, EV_REL) >= 0
        && ioctl(fd, UI_SET_RELBIT, REL_X) >= 0
        && ioctl(fd, UI_SET_RELBIT, REL_Y) >= 0
        && ioctl(fd, UI_SET_RELBIT, REL_WHEEL) >= 0
        && ioctl(fd, UI_SET_RELBIT, REL_HWHEEL) >= 0
        && ioctl(fd, UI_SET_EVBIT, EV_SYN) >= 0;
    if (!ok) { printf("fail uinput setup: %s\n", strerror(errno)); return 1; }

    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof dev);
    snprintf(dev.name, UINPUT_MAX_NAME_SIZE, "Couch Phone Mouse");
    dev.id.bustype = BUS_USB;
    dev.id.vendor = 0x1209;    /* pid.codes, the open source vendor id */
    dev.id.product = 0xc0c4;
    dev.id.version = 1;
    if (write(fd, &dev, sizeof dev) != (ssize_t) sizeof dev || ioctl(fd, UI_DEV_CREATE) < 0) {
        printf("fail uinput create: %s\n", strerror(errno));
        return 1;
    }
    printf("ready\n");

    char line[128];
    while (fgets(line, sizeof line, stdin)) {
        char cmd = line[0];
        if (cmd == 'm') {
            long dx = 0, dy = 0;
            if (sscanf(line + 1, "%ld %ld", &dx, &dy) == 2 && (dx || dy)) {
                if (dx) emit(EV_REL, REL_X, clamp(dx, 4000));
                if (dy) emit(EV_REL, REL_Y, clamp(dy, 4000));
                syn();
            }
        } else if (cmd == 'w') {
            long v = 0, h = 0;
            if (sscanf(line + 1, "%ld %ld", &v, &h) >= 1 && (v || h)) {
                if (v) emit(EV_REL, REL_WHEEL, clamp(v, 50));
                if (h) emit(EV_REL, REL_HWHEEL, clamp(h, 50));
                syn();
            }
        } else if (cmd == 'b' || cmd == 'c') {
            char which[8] = {0};
            int down = 0;
            int n = sscanf(line + 1, "%7s %d", which, &down);
            int code = button(which);
            if (n < 1 || code < 0) { printf("err bad button\n"); continue; }
            if (cmd == 'c') {
                emit(EV_KEY, code, 1); syn();
                usleep(15000);
                emit(EV_KEY, code, 0); syn();
            } else {
                emit(EV_KEY, code, down ? 1 : 0); syn();
            }
        } else if (cmd == 'p') {
            printf("pong\n");
        } else if (cmd != '\n' && cmd != '\r') {
            printf("err unknown command\n");
        }
    }
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return 0;
}
