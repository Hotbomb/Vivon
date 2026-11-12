package app.couchphone.root;

import android.os.SystemClock;
import android.util.Base64;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * The root helper. The app starts it once through `su` with app_process (the way scrcpy runs its
 * server), so it runs as root with the system's own input service at hand. It stays running and
 * reads one command per line on stdin, so a key press costs well under a millisecond instead of
 * the few hundred a fresh `input keyevent` process takes.
 *
 * Every event is stamped with a display id, so it goes to the DeX screen, not the phone's own.
 *
 * Commands:
 *   k <keycode> <display> <0|1 long press> [meta state, e.g. 4096 for Ctrl]
 *   t <display> <text as base64 UTF-8>
 *   s <0|2>                 the phone's own screen panel off (0) or on (2), the way scrcpy's
 *                           "turn screen off" does it: the phone stays awake and the TV keeps going
 *   mm <display> <x> <y>    the mouse moves to x, y (a hover, or a drag while a button is down)
 *   mb <display> <x> <y> <1 left|2 right|4 middle> <1 down|0 up>
 *   mw <display> <x> <y> <v> <h>   the wheel: v up (+) or down (-), h right (+) or left (-), in clicks
 *   p                       answers "pong"
 *
 * Original code for couch-phone. Nothing here is copied from another project.
 */
public final class Injector {
    private static final int MODE_ASYNC = 0;   // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
    private static Object inputManager;
    private static Method inject;
    private static Method setDisplayId;

    public static void main(String[] args) {
        try {
            Class<?> imc = Class.forName("android.hardware.input.InputManager");
            inputManager = imc.getDeclaredMethod("getInstance").invoke(null);
            inject = imc.getMethod("injectInputEvent", InputEvent.class, int.class);
            setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
        } catch (Throwable t) {
            System.out.println("fail " + t);
            System.out.flush();
            return;
        }
        System.out.println("ready");
        System.out.flush();
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    handle(line.trim());
                } catch (Throwable t) {
                    System.out.println("err " + t);
                    System.out.flush();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void handle(String line) throws Exception {
        if (line.isEmpty()) return;
        String[] p = line.split(" ");
        switch (p[0]) {
            case "p":
                System.out.println("pong");
                System.out.flush();
                break;
            case "k":
                key(Integer.parseInt(p[1]), Integer.parseInt(p[2]), p.length > 3 && "1".equals(p[3]), p.length > 4 ? Integer.parseInt(p[4]) : 0);
                break;
            case "mm":
                mouseMove(Integer.parseInt(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]));
                break;
            case "mb":
                mouseButton(Integer.parseInt(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]), Integer.parseInt(p[4]), "1".equals(p[5]));
                break;
            case "mw":
                mouseWheel(Integer.parseInt(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]));
                break;
            case "s":
                screen(Integer.parseInt(p[1]));
                break;
            case "t":
                text(Integer.parseInt(p[1]), new String(Base64.decode(p.length > 2 ? p[2] : "", Base64.DEFAULT), "UTF-8"));
                break;
            default:
                System.out.println("err unknown command");
                System.out.flush();
        }
    }

    private static void send(InputEvent e, int display) throws Exception {
        if (display >= 0) setDisplayId.invoke(e, display);
        inject.invoke(inputManager, e, MODE_ASYNC);
    }

    private static KeyEvent ev(long down, long now, int action, int code, int repeat, int flags, int meta) {
        return new KeyEvent(down, now, action, code, repeat, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                flags | KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
    }

    /** A key press. With Ctrl in meta (paste is Ctrl+V), the Ctrl key itself goes down first and up last. */
    private static void key(int code, int display, boolean longPress, int meta) throws Exception {
        long down = SystemClock.uptimeMillis();
        boolean ctrl = (meta & KeyEvent.META_CTRL_ON) != 0;
        if (ctrl) send(ev(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0, KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_ON), display);
        send(ev(down, down, KeyEvent.ACTION_DOWN, code, 0, 0, meta), display);
        if (longPress) {
            Thread.sleep(600);
            send(ev(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 1, KeyEvent.FLAG_LONG_PRESS, meta), display);
        }
        send(ev(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, meta), display);
        if (ctrl) send(ev(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0, 0), display);
    }

    // ---------------------------------------------------------------- the mouse
    // Couch Phone draws its own pointer on the TV (Cursor.kt) and sends real mouse events where it
    // is, so hover, clicks, right clicks, drags and the wheel work in Chrome and every other app.

    private static int mouseButtons = 0;
    private static long mouseDown = 0;
    private static Method setActionButton;

    private static MotionEvent mouseEvent(long down, int action, float x, float y, int buttons, float v, float h) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = buttons != 0 ? 1f : 0f;
        pc.size = 1f;
        if (v != 0) pc.setAxisValue(MotionEvent.AXIS_VSCROLL, v);
        if (h != 0) pc.setAxisValue(MotionEvent.AXIS_HSCROLL, h);
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(down, now, action, 1, new MotionEvent.PointerProperties[] { pp },
                new MotionEvent.PointerCoords[] { pc }, 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    }

    private static void mouseMove(int display, float x, float y) throws Exception {
        long t = mouseButtons != 0 ? mouseDown : SystemClock.uptimeMillis();
        send(mouseEvent(t, mouseButtons != 0 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE, x, y, mouseButtons, 0, 0), display);
    }

    private static void withActionButton(MotionEvent e, int button) {
        try {
            if (setActionButton == null) setActionButton = MotionEvent.class.getMethod("setActionButton", int.class);
            setActionButton.invoke(e, button);
        } catch (Throwable ignored) {
        }
    }

    private static void mouseButton(int display, float x, float y, int button, boolean down) throws Exception {
        if (down) {
            if ((mouseButtons & button) != 0) return;
            boolean first = mouseButtons == 0;
            mouseButtons |= button;
            if (first) {
                mouseDown = SystemClock.uptimeMillis();
                send(mouseEvent(mouseDown, MotionEvent.ACTION_DOWN, x, y, mouseButtons, 0, 0), display);
            }
            MotionEvent press = mouseEvent(mouseDown, MotionEvent.ACTION_BUTTON_PRESS, x, y, mouseButtons, 0, 0);
            withActionButton(press, button);
            send(press, display);
        } else {
            if ((mouseButtons & button) == 0) return;
            mouseButtons &= ~button;
            MotionEvent release = mouseEvent(mouseDown, MotionEvent.ACTION_BUTTON_RELEASE, x, y, mouseButtons, 0, 0);
            withActionButton(release, button);
            send(release, display);
            if (mouseButtons == 0) {
                send(mouseEvent(mouseDown, MotionEvent.ACTION_UP, x, y, 0, 0, 0), display);
                send(mouseEvent(SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0, 0), display);
            }
        }
    }

    private static void mouseWheel(int display, float x, float y, float v, float h) throws Exception {
        send(mouseEvent(SystemClock.uptimeMillis(), MotionEvent.ACTION_SCROLL, x, y, mouseButtons, v, h), display);
    }

    /**
     * Powers the phone's built-in panel off or on with SurfaceControl (hidden API, root only). The
     * TV is an external display with its own token, so it is never touched.
     */
    private static void screen(int mode) throws Exception {
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        Object token = null;
        try {
            token = sc.getMethod("getInternalDisplayToken").invoke(null);
        } catch (NoSuchMethodException e) {
            long[] ids = (long[]) sc.getMethod("getPhysicalDisplayIds").invoke(null);
            if (ids != null && ids.length > 0) token = sc.getMethod("getPhysicalDisplayToken", long.class).invoke(null, ids[0]);
        }
        if (token == null) throw new IllegalStateException("no built-in display");
        sc.getMethod("setDisplayPowerMode", android.os.IBinder.class, int.class).invoke(null, token, mode);
        System.out.println("screen " + mode);
        System.out.flush();
    }

    /** Types text with the virtual keyboard map. Characters it has no keys for are skipped. */
    private static void text(int display, String s) throws Exception {
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        int skipped = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            char[] chars = Character.toChars(cp);
            i += chars.length;
            KeyEvent[] events = map.getEvents(chars);
            if (events == null) { skipped++; continue; }
            for (KeyEvent e : events) {
                KeyEvent c = KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0);
                send(c, display);
            }
        }
        if (skipped > 0) {
            System.out.println("skipped " + skipped);
            System.out.flush();
        }
    }
}
