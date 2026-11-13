package io.github.simllll.panelelevate;

import android.os.Process;
import android.util.Log;

import io.github.simllll.panelelevate.helper.RootShell;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads hardware button events directly from /dev/input/eventX devices.
 *
 * This allows capturing button presses without needing an Activity in the foreground.
 * Requires the app to have permission to read from /dev/input/ (typically needs root or system permissions).
 *
 * The input_event struct on Linux is:
 * - struct timeval (8 or 16 bytes depending on 32/64 bit)
 * - __u16 type
 * - __u16 code
 * - __s32 value
 *
 * The kernel hands out the layout of the reading process: 24 bytes for 64-bit processes
 * (16 byte timeval), 16 bytes for 32-bit ones (compat layout, also on a 64-bit kernel).
 *
 * Besides key events, the start of a multi-touch contact (ABS_MT_TRACKING_ID != -1) is reported
 * as a touch, for touchscreens that do not send BTN_TOUCH. Devices whose /dev/input nodes are
 * root-only (NSPanel Pro) are read through a root shell running cat, if one is available.
 */
public class InputEventReader {

    private static final String TAG = "InputEventReader";
    private static final String INPUT_DIR = "/dev/input";

    // Event types
    private static final int EV_KEY = 0x01;
    private static final int EV_ABS = 0x03;

    // Event codes
    private static final int ABS_MT_TRACKING_ID = 0x39;

    // Event values
    private static final int KEY_RELEASED = 0;
    private static final int KEY_PRESSED = 1;
    private static final int KEY_REPEAT = 2;

    private final ButtonEventCallback callback;
    private final TouchCallback touchCallback;
    private final RootShell rootShell;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<InputStream> openStreams = new ArrayList<>();
    // A multi-touch device is being read (directly or via root)
    private volatile boolean touchReader = false;

    public interface ButtonEventCallback {
        void onButtonEvent(int keyCode, boolean pressed);
    }

    public interface TouchCallback {
        void onTouch();
    }

    public InputEventReader(ButtonEventCallback callback) {
        this(callback, null, null);
    }

    /**
     * @param touchCallback called when a touch contact starts (may be null)
     * @param rootShell     used to read input nodes the app cannot open itself (may be null)
     */
    public InputEventReader(ButtonEventCallback callback, TouchCallback touchCallback, RootShell rootShell) {
        this.callback = callback;
        this.touchCallback = touchCallback;
        this.rootShell = rootShell;
        this.executor = Executors.newCachedThreadPool();
    }

    /** True once a multi-touch input device is being read (touches arrive via onTouch). */
    public boolean hasTouchReader() {
        return touchReader;
    }

    private static String capabilitiesPath(String eventName) {
        return "/sys/class/input/" + eventName + "/device/capabilities/";
    }

    private static boolean hasTouchOrKeys(String eventName) {
        String base = capabilitiesPath(eventName);
        String keys = readFirstLine(base + "key");
        String abs = readFirstLine(base + "abs");
        // Capabilities unreadable: better read the device than miss a touchscreen
        if (keys == null && abs == null) return true;
        return hasTouchOrKeys(keys, abs);
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Decides from the sysfs capability bitmaps (hex words, most significant first) whether an
     * input device reports keys or multi-touch contacts (ABS_MT_TRACKING_ID). The word size is
     * the kernel's long: words longer than 8 hex digits mean 64 bit.
     */
    static boolean hasTouchOrKeys(String keyCaps, String absCaps) {
        if (keyCaps != null && !keyCaps.trim().replace("0", "").replace(" ", "").isEmpty()) return true;
        return hasMultiTouch(absCaps);
    }

    /** True if the abs capability bitmap contains ABS_MT_TRACKING_ID. */
    static boolean hasMultiTouch(String absCaps) {
        if (absCaps == null || absCaps.trim().isEmpty()) return false;
        String[] words = absCaps.trim().split("\\s+");
        boolean wide = false;
        for (String w : words) wide |= w.length() > 8;
        try {
            if (wide) {
                long low = Long.parseUnsignedLong(words[words.length - 1], 16);
                return (low & (1L << ABS_MT_TRACKING_ID)) != 0;
            }
            if (words.length < 2) return false;
            long word = Long.parseLong(words[words.length - 2], 16);
            return (word & (1L << (ABS_MT_TRACKING_ID - 32))) != 0;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** Size of one struct input_event as seen by this (and every same-ABI) process. */
    static int eventSize(boolean is64BitProcess) {
        return is64BitProcess ? 24 : 16;
    }

    public void start() {
        if (running.getAndSet(true)) {
            Log.w(TAG, "InputEventReader already running");
            return;
        }

        Log.i(TAG, "Starting InputEventReader...");

        File inputDir = new File(INPUT_DIR);
        if (!inputDir.exists() || !inputDir.isDirectory()) {
            Log.e(TAG, "Input directory not found: " + INPUT_DIR);
            return;
        }

        File[] eventFiles = inputDir.listFiles((dir, name) -> name.startsWith("event"));
        if (eventFiles == null || eventFiles.length == 0) {
            Log.e(TAG, "No input event files found");
            return;
        }

        for (File eventFile : eventFiles) {
            if (!eventFile.canRead()) {
                if (rootShell != null && !hasTouchOrKeys(eventFile.getName())) {
                    // e.g. the light sensor: a stream of ABS events nobody needs through a pipe
                    Log.i(TAG, "Skipping " + eventFile.getAbsolutePath() + " (no keys, no touch)");
                } else if (rootShell != null) {
                    Log.i(TAG, "Starting root reader for: " + eventFile.getAbsolutePath());
                    touchReader |= hasMultiTouch(readFirstLine(capabilitiesPath(eventFile.getName()) + "abs"));
                    executor.submit(() -> readEvents(eventFile, true));
                } else {
                    Log.w(TAG, "Cannot read: " + eventFile.getAbsolutePath() + " (need root?)");
                }
                continue;
            }

            Log.i(TAG, "Starting reader for: " + eventFile.getAbsolutePath());
            touchReader |= hasMultiTouch(readFirstLine(capabilitiesPath(eventFile.getName()) + "abs"));
            executor.submit(() -> readEvents(eventFile, false));
        }
    }

    public void stop() {
        Log.i(TAG, "Stopping InputEventReader...");
        running.set(false);

        // Close all open streams to unblock reads
        synchronized (openStreams) {
            for (InputStream fis : openStreams) {
                try {
                    fis.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
            openStreams.clear();
        }

        executor.shutdownNow();
    }

    private void readEvents(File eventFile, boolean viaRoot) {
        Log.i(TAG, "Reading events from: " + eventFile.getAbsolutePath() + (viaRoot ? " (root)" : ""));

        InputStream opened = null;
        try {
            opened = viaRoot ? rootShell.openStream(eventFile.getAbsolutePath()) : new FileInputStream(eventFile);
        } catch (IOException e) {
            Log.e(TAG, "Failed to open " + eventFile.getAbsolutePath() + ": " + e.getMessage());
        }
        if (opened == null) {
            if (viaRoot) Log.w(TAG, "Cannot read " + eventFile.getAbsolutePath() + " via root either");
            return;
        }

        try (InputStream fis = opened) {
            synchronized (openStreams) {
                openStreams.add(fis);
            }

            // Buffer for input_event struct
            // On 32-bit: timeval(8) + type(2) + code(2) + value(4) = 16 bytes
            // On 64-bit: timeval(16) + type(2) + code(2) + value(4) = 24 bytes
            // A root reader (cat) has the system's primary ABI, the same as our process.
            int eventSize = eventSize(Process.is64Bit());
            byte[] buffer = new byte[eventSize];
            DataInputStream dis = new DataInputStream(fis);

            while (running.get()) {
                try {
                    // Read full event
                    dis.readFully(buffer);

                    ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

                    // Skip timeval (16 bytes on 64-bit, 8 on 32-bit)
                    bb.position(eventSize - 8);

                    int type = bb.getShort() & 0xFFFF;
                    int code = bb.getShort() & 0xFFFF;
                    int value = bb.getInt();

                    if (type == EV_ABS && code == ABS_MT_TRACKING_ID) {
                        // New contact; -1 marks its end
                        if (value != -1 && touchCallback != null) {
                            touchCallback.onTouch();
                        }
                    } else if (type == EV_KEY) {
                        boolean pressed = (value == KEY_PRESSED);
                        boolean released = (value == KEY_RELEASED);

                        if (pressed || released) {
                            Log.d(TAG, "Key event: code=" + code + ", pressed=" + pressed + " from " + eventFile.getName());

                            if (callback != null) {
                                callback.onButtonEvent(code, pressed);
                            }
                        }
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        Log.e(TAG, "Error reading from " + eventFile.getName() + ": " + e.getMessage());
                    }
                    break;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to open " + eventFile.getAbsolutePath() + ": " + e.getMessage());
        } finally {
            synchronized (openStreams) {
                // Remove from list (stream already closed by try-with-resources)
            }
        }

        Log.i(TAG, "Stopped reading from: " + eventFile.getAbsolutePath());
    }
}
