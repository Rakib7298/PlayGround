package dev.rakib.pandamobile.engine;

/**
 * Optional JNI bridge to a native Lightpanda core.
 *
 * <p>The current APK ships without a native core, so {@link #isAvailable()}
 * returns {@code false} and the app uses the Android WebView for rendering.
 * A future build can drop {@code liblightpanda_jni.so} into
 * {@code app/src/main/jniLibs/arm64-v8a/} and implement this contract in C:
 *
 * <pre>
 * JNIEXPORT jint JNICALL Java_dev_rakib_pandamobile_engine_NativeCore_start(
 *     JNIEnv *, jclass, jstring data_dir, jint port);
 * JNIEXPORT void JNICALL Java_dev_rakib_pandamobile_engine_NativeCore_stop(JNIEnv *, jclass);
 * JNIEXPORT jstring JNICALL Java_dev_rakib_pandamobile_engine_NativeCore_version(JNIEnv *, jclass);
 * </pre>
 *
 * <p>The intended design is a local CDP endpoint on loopback (`127.0.0.1:port`),
 * started on a dedicated thread, with the Android UI acting as the CDP client.
 * Keeping the boundary at CDP avoids exposing Zig/V8 internals through JNI and
 * lets the same core run on a desktop/server.
 *
 * <p>Licensing: Lightpanda is AGPL-3.0. Linking this library into the app makes
 * the combined work subject to the AGPL's source-disclosure obligations unless a
 * commercial license is obtained from Lightpanda.
 */
public final class NativeCore {

    private static final String LIBRARY = "lightpanda_jni";
    private static Boolean available;

    private NativeCore() {
    }

    public static synchronized boolean isAvailable() {
        if (available == null) {
            try {
                System.loadLibrary(LIBRARY);
                available = Boolean.TRUE;
            } catch (UnsatisfiedLinkError | SecurityException e) {
                available = Boolean.FALSE;
            }
        }
        return available;
    }

    /** Starts the core and returns the bound loopback port, or -1 on failure. */
    public static native int start(String dataDir, int preferredPort);

    /** Stops the core and releases its browser sessions. */
    public static native void stop();

    /** Returns the upstream engine version string, or "unknown". */
    public static native String version();
}
