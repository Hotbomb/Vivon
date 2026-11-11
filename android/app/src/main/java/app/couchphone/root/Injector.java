package app.couchphone.root;

import android.os.SystemClock;
import android.util.Base64;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

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
