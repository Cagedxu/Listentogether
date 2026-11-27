package com.musicmod.client;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.List;

/**
 * Windows low-level keyboard hook for the hardware volume keys.
 *
 * <p>Only the volume up/down/mute keys are consumed, and only while the
 * Minecraft window is in the foreground. The event is then applied to the
 * mod's own playback volume; the system volume is left untouched. Outside
 * the game, or while another window is focused, the keys behave normally.</p>
 */
public final class VolumeKeyHook {
    private static final int WH_KEYBOARD_LL = 13;
    private static final int WM_KEYDOWN = 0x0100;
    private static final int WM_SYSKEYDOWN = 0x0104;
    private static final int VK_VOLUME_MUTE = 0xAD;
    private static final int VK_VOLUME_DOWN = 0xAE;
    private static final int VK_VOLUME_UP = 0xAF;
    private static final float STEP = 0.05F;

    private static volatile boolean started;
    private static volatile boolean running;
    private static WinUser.HHOOK hook;
    private static Thread thread;
    private static float lastNonZero = 0.5F;
    private static final HookProc CALLBACK = new HookProc();

    private VolumeKeyHook() {
    }

    public static synchronized void start() {
        if (started) return;
        started = true;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return;
        running = true;
        thread = new Thread(VolumeKeyHook::loop, "musicmod-volume-keys");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loop() {
        try {
            WinDef.HINSTANCE instance = new WinDef.HINSTANCE();
            try {
                NativeLibrary jna = NativeLibrary.getInstance("jnidispatch");
                WinNT.HMODULE module = Kernel32.INSTANCE.GetModuleHandle(jna.getFile().getName());
                if (module != null) instance.setPointer(module.getPointer());
            } catch (Throwable t) {
                WinNT.HMODULE module = Kernel32.INSTANCE.GetModuleHandle(null);
                if (module != null) instance.setPointer(module.getPointer());
            }
            hook = User32.INSTANCE.SetWindowsHookEx(WH_KEYBOARD_LL, CALLBACK, instance, 0);
            if (hook == null) {
                running = false;
                int err = Native.getLastError();
                System.err.println("Volume key hook unavailable (error " + err + ")");
                return;
            }
            System.err.println("Volume key hook installed");
            WinUser.MSG msg = new WinUser.MSG();
            while (running && User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                User32.INSTANCE.TranslateMessage(msg);
                User32.INSTANCE.DispatchMessage(msg);
            }
        } catch (Throwable t) {
            System.err.println("Volume key hook failed: " + t);
        } finally {
            if (hook != null) {
                User32.INSTANCE.UnhookWindowsHookEx(hook);
                hook = null;
            }
            running = false;
        }
    }

    private static boolean isGameForeground() {
        try {
            WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) return false;
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            return pid.getValue() == (int) ProcessHandle.current().pid();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void adjust(int vk) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            ClientMusicManager manager = ClientMusicManager.getInstance();
            float value = manager.getVolume();
            if (vk == VK_VOLUME_MUTE) {
                if (value > 0.001F) {
                    lastNonZero = value;
                    manager.setVolume(0.0F);
                } else {
                    manager.setVolume(lastNonZero > 0.001F ? lastNonZero : 0.5F);
                }
                return;
            }
            if (value > 0.001F) lastNonZero = value;
            float next = vk == VK_VOLUME_UP ? value + STEP : value - STEP;
            next = Math.max(0.0F, Math.min(1.0F, next));
            manager.setVolume(next);
        });
    }

    public static final class HookProc implements WinUser.HOOKPROC {
        public WinDef.LRESULT callback(int nCode, WinDef.WPARAM wParam, WinDef.LPARAM lParam) {
            if (nCode == 0 && running) {
                int msg = wParam.intValue() & 0xFFFF;
                if (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN) {
                    KBDLLHOOKSTRUCT info = new KBDLLHOOKSTRUCT(new Pointer(lParam.longValue()));
                    info.read();
                    int vk = info.vkCode;
                    if ((vk == VK_VOLUME_UP || vk == VK_VOLUME_DOWN || vk == VK_VOLUME_MUTE)
                            && isGameForeground()) {
                        adjust(vk);
                        return new WinDef.LRESULT(1);
                    }
                }
            }
            return User32.INSTANCE.CallNextHookEx(hook, nCode, wParam, lParam);
        }
    }

    public static final class KBDLLHOOKSTRUCT extends Structure {
        public int vkCode;
        public int scanCode;
        public int flags;
        public int time;
        public WinNT.ULONG_PTR dwExtraInfo;

        public KBDLLHOOKSTRUCT(Pointer p) {
            super(p);
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("vkCode", "scanCode", "flags", "time", "dwExtraInfo");
        }
    }
}
