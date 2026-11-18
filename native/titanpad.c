/*
 * titanpad - tiny privileged helper for TitanPointer.
 *
 * Reads raw evdev events from the Unihertz Titan's touch-sensitive keyboard
 * (or any other input device) and streams them as text lines to the app.
 * It must run as root (via su) or as the adb shell user, because normal apps
 * cannot open /dev/input/event*.
 *
 * Modes:
 *   titanpad stdio                 commands on stdin, replies on stdout (root mode)
 *   titanpad serve <port> <token>  listen on 127.0.0.1:<port> (adb mode)
 *
 * Commands (one per line):
 *   auth <token>     (serve mode only, must be first)
 *   list             -> "D <path>\t<name>\t<props>\t<mt>\t<xmin> <xmax> <ymin> <ymax>\t<slots>\t<letterkeys>" ... "L end"
 *   open <path>      -> "O ok <xmin> <xmax> <ymin> <ymax> <slots> <mt>" | "O err <msg>", then "E <type> <code> <value>" lines
 *   grab <0|1>       -> "G ok <0|1>" | "G err <msg>"
 *   close            -> "C ok"
 *   ping             -> "P ok"
 *
 * The device is always closed (and so un-grabbed) when the client goes away.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <unistd.h>

#define BITS_PER_LONG (sizeof(long) * 8)
#define NBITS(x) ((((x) - 1) / BITS_PER_LONG) + 1)
#define TEST_BIT(bit, array) ((array[(bit) / BITS_PER_LONG] >> ((bit) % BITS_PER_LONG)) & 1)

static int out_fd = 1;
static char outbuf[65536];
static size_t outlen = 0;
static int dev_fd = -1;
static int grabbed = 0;

static int flush_out(void) {
    size_t off = 0;
    while (off < outlen) {
        ssize_t n = write(out_fd, outbuf + off, outlen - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            outlen = 0;
            return -1;
        }
        off += (size_t)n;
    }
    outlen = 0;
    return 0;
}

static int emit(const char *fmt, ...) {
    char line[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    if (n < 0) return 0;
    if ((size_t)n >= sizeof(line)) n = sizeof(line) - 1;
    if (outlen + (size_t)n > sizeof(outbuf)) {
        if (flush_out() < 0) return -1;
    }
    memcpy(outbuf + outlen, line, (size_t)n);
    outlen += (size_t)n;
    return 0;
}

static void sanitize(char *s) {
    for (; *s; s++) if (*s == '\t' || *s == '\n' || *s == '\r') *s = ' ';
}

struct devinfo {
    char name[256];
    unsigned long props[NBITS(INPUT_PROP_CNT)];
    int mt;
    int xmin, xmax, ymin, ymax, slots, letters;
    int has_pos;
};

static void probe(int fd, struct devinfo *d) {
    unsigned long absbits[NBITS(ABS_CNT)];
    unsigned long keybits[NBITS(KEY_CNT)];
    struct input_absinfo ai;
    memset(d, 0, sizeof(*d));
    memset(absbits, 0, sizeof(absbits));
    memset(keybits, 0, sizeof(keybits));
    if (ioctl(fd, EVIOCGNAME(sizeof(d->name) - 1), d->name) < 0) strcpy(d->name, "?");
    sanitize(d->name);
    ioctl(fd, EVIOCGPROP(sizeof(d->props)), d->props);
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits);
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits);
    int xa = -1, ya = -1;
    if (TEST_BIT(ABS_MT_POSITION_X, absbits) && TEST_BIT(ABS_MT_POSITION_Y, absbits)) {
        d->mt = 1; xa = ABS_MT_POSITION_X; ya = ABS_MT_POSITION_Y;
    } else if (TEST_BIT(ABS_X, absbits) && TEST_BIT(ABS_Y, absbits)) {
        xa = ABS_X; ya = ABS_Y;
    }
    if (xa >= 0) {
        d->has_pos = 1;
        if (ioctl(fd, EVIOCGABS(xa), &ai) == 0) { d->xmin = ai.minimum; d->xmax = ai.maximum; }
        if (ioctl(fd, EVIOCGABS(ya), &ai) == 0) { d->ymin = ai.minimum; d->ymax = ai.maximum; }
    }
    d->slots = 1;
    if (TEST_BIT(ABS_MT_SLOT, absbits) && ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &ai) == 0)
        d->slots = ai.maximum + 1;
    static const int letters[] = {KEY_Q, KEY_W, KEY_E, KEY_R, KEY_T, KEY_Y, KEY_U, KEY_I, KEY_O, KEY_P,
                                  KEY_A, KEY_S, KEY_D, KEY_F, KEY_G, KEY_H, KEY_J, KEY_K, KEY_L,
                                  KEY_Z, KEY_X, KEY_C, KEY_V, KEY_B, KEY_N, KEY_M};
    for (size_t i = 0; i < sizeof(letters) / sizeof(letters[0]); i++)
        if (TEST_BIT(letters[i], keybits)) d->letters++;
}

static unsigned long props_word(struct devinfo *d) { return d->props[0]; }

static void cmd_list(void) {
    DIR *dir = opendir("/dev/input");
    if (!dir) { emit("L err %s\n", strerror(errno)); return; }
    struct dirent *e;
    while ((e = readdir(dir)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[300];
        snprintf(path, sizeof(path), "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) { emit("D %s\t(cannot open: %s)\t0\t0\t0 0 0 0\t0\t0\n", path, strerror(errno)); continue; }
        struct devinfo d;
        probe(fd, &d);
        close(fd);
        emit("D %s\t%s\t%lu\t%d\t%d %d %d %d\t%d\t%d\n", path, d.name, props_word(&d), d.has_pos ? (d.mt ? 2 : 1) : 0,
             d.xmin, d.xmax, d.ymin, d.ymax, d.slots, d.letters);
    }
    closedir(dir);
    emit("L end\n");
}

static void close_dev(void) {
    if (dev_fd >= 0) {
        if (grabbed) ioctl(dev_fd, EVIOCGRAB, (void *)0);
        close(dev_fd);
    }
    dev_fd = -1;
    grabbed = 0;
}

static void cmd_open(const char *path) {
    close_dev();
    if (strncmp(path, "/dev/input/event", 16) != 0 || strstr(path, "..")) {
        emit("O err bad path\n");
        return;
    }
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) { emit("O err %s\n", strerror(errno)); return; }
    struct devinfo d;
    probe(fd, &d);
    dev_fd = fd;
    emit("O ok %d %d %d %d %d %d\n", d.xmin, d.xmax, d.ymin, d.ymax, d.slots, d.mt);
    /* Report the current slot state so the client starts in sync. */
    if (d.mt) {
        struct input_absinfo ai;
        if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &ai) == 0) emit("S %d\n", ai.value);
    }
}

static void cmd_grab(int on) {
    if (dev_fd < 0) { emit("G err not open\n"); return; }
    if (on == grabbed) { emit("G ok %d\n", on); return; }
    if (ioctl(dev_fd, EVIOCGRAB, (void *)(long)on) < 0) { emit("G err %s\n", strerror(errno)); return; }
    grabbed = on;
    emit("G ok %d\n", on);
}

/* Returns 0 to continue, -1 to drop the client. */
static int handle_line(char *line, int *authed, const char *token) {
    size_t l = strlen(line);
    while (l && (line[l - 1] == '\r' || line[l - 1] == ' ')) line[--l] = 0;
    if (!*authed) {
        if (strncmp(line, "auth ", 5) == 0 && token && strcmp(line + 5, token) == 0) {
            *authed = 1;
            emit("A ok\n");
            return 0;
        }
        emit("A err\n");
        return -1;
    }
    if (strcmp(line, "list") == 0) cmd_list();
    else if (strncmp(line, "open ", 5) == 0) cmd_open(line + 5);
    else if (strncmp(line, "grab ", 5) == 0) cmd_grab(atoi(line + 5) ? 1 : 0);
    else if (strcmp(line, "close") == 0) { close_dev(); emit("C ok\n"); }
    else if (strcmp(line, "ping") == 0) emit("P ok\n");
    else if (*line) emit("X unknown command\n");
    return 0;
}

/* Serve one client on in_fd/out_fd until it disconnects. */
static void session(int in_fd, int authed, const char *token) {
    char inbuf[4096];
    size_t inlen = 0;
    for (;;) {
        if (flush_out() < 0) break;
        struct pollfd p[2];
        int n = 0;
        p[n].fd = in_fd; p[n].events = POLLIN; p[n].revents = 0; n++;
        if (dev_fd >= 0) { p[n].fd = dev_fd; p[n].events = POLLIN; p[n].revents = 0; n++; }
        if (poll(p, n, -1) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n > 1 && (p[1].revents & (POLLERR | POLLHUP | POLLNVAL))) {
            emit("O err device gone\n");
            close_dev();
        } else if (n > 1 && (p[1].revents & POLLIN)) {
            struct input_event ev[64];
            ssize_t r = read(dev_fd, ev, sizeof(ev));
            if (r > 0) {
                int cnt = (int)(r / (ssize_t)sizeof(struct input_event));
                for (int i = 0; i < cnt; i++)
                    if (emit("E %d %d %d\n", ev[i].type, ev[i].code, ev[i].value) < 0) goto done;
            } else if (r < 0 && errno != EAGAIN && errno != EINTR) {
                emit("O err %s\n", strerror(errno));
                close_dev();
            }
        }
        if (p[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t r = read(in_fd, inbuf + inlen, sizeof(inbuf) - 1 - inlen);
            if (r <= 0) {
                if (r < 0 && errno == EINTR) continue;
                break;
            }
            inlen += (size_t)r;
            inbuf[inlen] = 0;
            char *start = inbuf, *nl;
            while ((nl = strchr(start, '\n')) != NULL) {
                *nl = 0;
                if (handle_line(start, &authed, token) < 0) { flush_out(); goto done; }
                start = nl + 1;
            }
            inlen = strlen(start);
            memmove(inbuf, start, inlen);
            if (inlen >= sizeof(inbuf) - 1) inlen = 0; /* overlong line: drop */
        }
    }
done:
    close_dev();
    outlen = 0;
}

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);
    if (argc >= 2 && strcmp(argv[1], "stdio") == 0) {
        out_fd = 1;
        emit("H titanpad 1 uid=%d\n", (int)getuid());
        session(0, 1, NULL);
        return 0;
    }
    if (argc >= 4 && strcmp(argv[1], "serve") == 0) {
        int port = atoi(argv[2]);
        const char *token = argv[3];
        int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (s < 0) { perror("socket"); return 1; }
        int one = 1;
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
        struct sockaddr_in a;
        memset(&a, 0, sizeof(a));
        a.sin_family = AF_INET;
        a.sin_port = htons((unsigned short)port);
        a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (bind(s, (struct sockaddr *)&a, sizeof(a)) < 0) { perror("bind"); return 1; }
        if (listen(s, 1) < 0) { perror("listen"); return 1; }
        fprintf(stderr, "titanpad: listening on 127.0.0.1:%d\n", port);
        for (;;) {
            int c = accept4(s, NULL, NULL, SOCK_CLOEXEC);
            if (c < 0) { if (errno == EINTR) continue; perror("accept"); sleep(1); continue; }
            out_fd = c;
            emit("H titanpad 1 uid=%d\n", (int)getuid());
            session(c, 0, token);
            close(c);
        }
    }
    if (argc >= 2 && strcmp(argv[1], "list") == 0) {
        int authed = 1;
        char cmd[] = "list";
        handle_line(cmd, &authed, NULL);
        flush_out();
        return 0;
    }
    fprintf(stderr, "usage: %s stdio | serve <port> <token> | list\n", argv[0]);
    return 2;
}
