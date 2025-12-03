package com.stretter.shellyelevateservice;

import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
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
 * For Android, we assume 32-bit timeval (8 bytes) + 2 + 2 + 4 = 16 bytes per event
 */
public class InputEventReader {

    private static final String TAG = "InputEventReader";
    private static final String INPUT_DIR = "/dev/input";

    // Event types
    private static final int EV_KEY = 0x01;

    // Event values
    private static final int KEY_RELEASED = 0;
    private static final int KEY_PRESSED = 1;
    private static final int KEY_REPEAT = 2;

    private final ButtonEventCallback callback;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<FileInputStream> openStreams = new ArrayList<>();

    public interface ButtonEventCallback {
        void onButtonEvent(int keyCode, boolean pressed);
    }

    public InputEventReader(ButtonEventCallback callback) {
        this.callback = callback;
        this.executor = Executors.newCachedThreadPool();
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
                Log.w(TAG, "Cannot read: " + eventFile.getAbsolutePath() + " (need root?)");
                continue;
            }

            Log.i(TAG, "Starting reader for: " + eventFile.getAbsolutePath());
            executor.submit(() -> readEvents(eventFile));
        }
    }

    public void stop() {
        Log.i(TAG, "Stopping InputEventReader...");
        running.set(false);

        // Close all open streams to unblock reads
        synchronized (openStreams) {
            for (FileInputStream fis : openStreams) {
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

    private void readEvents(File eventFile) {
        Log.i(TAG, "Reading events from: " + eventFile.getAbsolutePath());

        try (FileInputStream fis = new FileInputStream(eventFile)) {
            synchronized (openStreams) {
                openStreams.add(fis);
            }

            // Buffer for input_event struct
            // On 32-bit: timeval(8) + type(2) + code(2) + value(4) = 16 bytes
            // On 64-bit: timeval(16) + type(2) + code(2) + value(4) = 24 bytes
            // Try 24 bytes first (64-bit Android), fall back to 16 if needed
            byte[] buffer = new byte[24];
            DataInputStream dis = new DataInputStream(fis);

            while (running.get()) {
                try {
                    // Read full event
                    dis.readFully(buffer);

                    // Parse event (assuming 64-bit layout: 16 byte timeval)
                    ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

                    // Skip timeval (16 bytes on 64-bit)
                    bb.position(16);

                    int type = bb.getShort() & 0xFFFF;
                    int code = bb.getShort() & 0xFFFF;
                    int value = bb.getInt();

                    if (type == EV_KEY) {
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
