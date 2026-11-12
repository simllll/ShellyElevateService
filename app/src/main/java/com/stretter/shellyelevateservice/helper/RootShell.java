package com.stretter.shellyelevateservice.helper;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Persistent root shell for devices whose hardware nodes are root-only (NSPanel Pro backlight,
 * /dev/input, reboot).
 *
 * One long-lived {@code su} process is started lazily and fed commands on stdin, so a brightness
 * change costs a pipe write instead of a new process. All commands run on one thread, in order.
 * If su is missing or denies us (the AOSP userdebug su only allows uid root/shell), the shell is
 * marked unavailable after {@link #MAX_START_ATTEMPTS} failed starts and every command runs its
 * fallback instead. A shell that dies later is restarted on the next command.
 *
 * Plain {@code su} without arguments is used on purpose: AOSP su does not know {@code -c}, but
 * every su variant (AOSP, SuperSU, Magisk) reads commands from stdin.
 */
public class RootShell {
    private static final String TAG = "RootShell";

    static final String[] SU_PATHS = {
            "/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su", "/debug_ramdisk/su"
    };
    static final String READY_MARKER = "__shellyelevate_root_ready__";
    private static final long HANDSHAKE_TIMEOUT_MS = 5_000;
    private static final int MAX_START_ATTEMPTS = 3;

    // Only plain absolute paths and package names ever reach the shell
    private static final Pattern SAFE_PATH = Pattern.compile("/[A-Za-z0-9_./-]+");
    private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    private static volatile RootShell instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RootShell");
        t.setDaemon(true);
        return t;
    });
    // Only touched on the executor thread
    private Process process;
    private OutputStream stdin;
    private int failedStarts = 0;
    // null = not tried yet
    private volatile Boolean available;

    public static RootShell get() {
        if (instance == null) {
            synchronized (RootShell.class) {
                if (instance == null) instance = new RootShell();
            }
        }
        return instance;
    }

    // === Command building (pure, unit-tested) ===

    /** First existing su binary, or null. */
    static String findSu(Predicate<String> exists) {
        for (String path : SU_PATHS) {
            if (exists.test(path)) return path;
        }
        return null;
    }

    static boolean isSafePath(String path) {
        return path != null && SAFE_PATH.matcher(path).matches() && !path.contains("..");
    }

    /** Writes the brightness (clamped to 0..255) to a sysfs backlight node. */
    static String backlightCommand(String path, int brightness) {
        if (!isSafePath(path)) throw new IllegalArgumentException("unsafe path: " + path);
        int value = Math.max(0, Math.min(255, brightness));
        return "echo " + value + " > " + path;
    }

    /** Replaces the shell with cat, so the process stdout is the raw content of the node. */
    static String catCommand(String path) {
        if (!isSafePath(path)) throw new IllegalArgumentException("unsafe path: " + path);
        return "exec cat " + path;
    }

    static String forceStopCommand(String packageName) {
        if (packageName == null || !PACKAGE_NAME.matcher(packageName).matches()) {
            throw new IllegalArgumentException("invalid package name: " + packageName);
        }
        return "am force-stop " + packageName;
    }

    static String rebootCommand() {
        return "reboot";
    }

    // === Public API ===

    /** true/false once a start was attempted, null before. Never blocks. */
    public Boolean isAvailable() {
        return available;
    }

    /** True if an su binary exists at all (cheap, no process started). */
    public static boolean hasSuBinary() {
        return findSu(path -> new File(path).exists()) != null;
    }

    /** Starts the shell in the background so the first real command does not wait for it. */
    public void prepareAsync() {
        submit(this::ensureStarted);
    }

    public void setBacklight(String path, int brightness, Runnable fallback) {
        runOrElse(backlightCommand(path, brightness), fallback);
    }

    public void forceStop(String packageName, Runnable fallback) {
        runOrElse(forceStopCommand(packageName), fallback);
    }

    public void reboot(Runnable fallback) {
        runOrElse(rebootCommand(), fallback);
    }

    /**
     * Runs a command in the root shell (asynchronously, in order). If root is not available the
     * fallback runs instead (on the shell thread), if any.
     */
    public void runOrElse(String command, Runnable fallback) {
        submit(() -> {
            if (write(command)) return;
            if (fallback != null) {
                try {
                    fallback.run();
                } catch (Exception e) {
                    Log.e(TAG, "Fallback for '" + command + "' failed", e);
                }
            }
        });
    }

    /**
     * Opens a root-only file for streaming reads (e.g. /dev/input/eventX) through its own su
     * process running cat. Blocking; closing the returned stream ends the process. Returns null
     * if root is not available.
     */
    public InputStream openStream(String path) {
        if (Boolean.FALSE.equals(available) || !isSafePath(path)) return null;
        String su = findSu(p -> new File(p).exists());
        if (su == null) return null;
        try {
            Process p = new ProcessBuilder(su).start();
            OutputStream in = p.getOutputStream();
            in.write((catCommand(path) + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
            InputStream out = p.getInputStream();
            return new InputStream() {
                @Override public int read() throws IOException { return out.read(); }
                @Override public int read(byte[] b, int off, int len) throws IOException { return out.read(b, off, len); }
                @Override public void close() throws IOException {
                    p.destroy();
                    out.close();
                }
            };
        } catch (IOException e) {
            Log.w(TAG, "Could not open " + path + " via su: " + e.getMessage());
            return null;
        }
    }

    // === Internals (executor thread) ===

    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // shut down
        }
    }

    /** Writes one command line; restarts a dead shell once. False if root is unavailable. */
    private boolean write(String command) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!ensureStarted()) return false;
            try {
                stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                Log.d(TAG, "# " + command);
                return true;
            } catch (IOException e) {
                Log.w(TAG, "Root shell died (" + e.getMessage() + "), restarting");
                stopProcess();
            }
        }
        return false;
    }

    private boolean ensureStarted() {
        if (process != null && isAlive(process)) return true;
        stopProcess();
        if (Boolean.FALSE.equals(available)) return false;

        String su = findSu(path -> new File(path).exists());
        if (su == null) {
            Log.i(TAG, "No su binary found, root features disabled");
            available = false;
            return false;
        }

        try {
            Process p = new ProcessBuilder(su).redirectErrorStream(true).start();
            CountDownLatch answered = new CountDownLatch(1);
            AtomicBoolean ready = new AtomicBoolean(false);
            startOutputDrain(p.getInputStream(), answered, ready);
            OutputStream in = p.getOutputStream();
            in.write(("echo " + READY_MARKER + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
            answered.await(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (ready.get() && isAlive(p)) {
                process = p;
                stdin = in;
                failedStarts = 0;
                available = true;
                Log.i(TAG, "Root shell started (" + su + ")");
                return true;
            }
            p.destroy();
            Log.w(TAG, "su did not answer (denied?)");
        } catch (IOException e) {
            Log.w(TAG, "Could not start su: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        if (++failedStarts >= MAX_START_ATTEMPTS) {
            Log.w(TAG, "Root shell unavailable after " + failedStarts + " attempts, using fallbacks");
            available = false;
        }
        return false;
    }

    // Logs shell output (errors of our commands) and signals the handshake marker, or the end of
    // the output when su refuses and exits ("su: uid 10057 not allowed to su")
    private static void startOutputDrain(InputStream output, CountDownLatch answered, AtomicBoolean ready) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(output, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(READY_MARKER)) {
                        ready.set(true);
                        answered.countDown();
                    } else {
                        Log.w(TAG, "su: " + line);
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
            answered.countDown();
        }, "RootShell-output");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private void stopProcess() {
        if (process != null) {
            process.destroy();
            process = null;
            stdin = null;
        }
    }
}
