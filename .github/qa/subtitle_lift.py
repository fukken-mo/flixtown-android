"""Subtitle box checks for player_seasonal.sh. Boxes are "x0 y0 x1 y1 W H count" (screen pixels)."""
import sys


def box(s):
    return list(map(int, s.split()))


def lifted(hidden, shown, bar, controls):
    """Controls up: the same cue sits wholly above the control bar, on screen, not clipped or resized."""
    a, b = box(hidden), box(shown)
    bar = int(bar or 0)
    w, h = b[4], b[5]
    print("bar_top=%d hidden=%s shown=%s" % (bar, a[:4], b[:4]), file=sys.stderr)
    return (controls == "true" and bar > h * 0.5 and a[6] > 20 and b[6] > 20
            and b[3] <= bar - 2                                   # bottom of the text above the bar
            and b[1] >= h * 0.05 and b[0] >= w * 0.05 and b[2] <= w * 0.95   # whole text inside the safe area
            and abs((b[2] - b[0]) - (a[2] - a[0])) <= 6          # same width ...
            and abs((b[3] - b[1]) - (a[3] - a[1])) <= 6          # ... and height
            and abs(b[6] - a[6]) <= max(8, a[6] * 0.08)          # same amount of text drawn (nothing cut off)
            and b[3] < a[3] - 20)                                 # it really moved up


def restored(hidden, back):
    """Controls hidden again: the cue is back exactly where it was."""
    a, c = box(hidden), box(back)
    return (a[6] > 20 and c[6] > 20 and all(abs(a[i] - c[i]) <= 4 for i in range(4))
            and abs(a[6] - c[6]) <= max(8, a[6] * 0.08))


if __name__ == "__main__":
    fn = {"lifted": lifted, "restored": restored}[sys.argv[1]]
    sys.exit(0 if fn(*sys.argv[2:]) else 1)
