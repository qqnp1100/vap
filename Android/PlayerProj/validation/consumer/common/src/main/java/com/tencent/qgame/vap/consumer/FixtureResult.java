package com.tencent.qgame.vap.consumer;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;

/** Keeps playback assertions readable on devices that filter application logcat. */
public final class FixtureResult {
    private final File file;
    private final String tag;

    public FixtureResult(File filesDir, String tag) {
        this.file = new File(filesDir, "result.log");
        this.tag = tag;
        try (FileOutputStream reset = new FileOutputStream(file, false)) {
            reset.flush();
        } catch (IOException error) {
            Log.e(tag, "Could not reset fixture result", error);
        }
    }

    public void note(String message) {
        Log.i(tag, message);
        append(message);
    }

    public void fail(String message, Throwable cause) {
        Log.e(tag, message, cause);
        append(cause == null ? message : message + " cause=" + cause);
    }

    private synchronized void append(String message) {
        try (OutputStreamWriter output = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8")) {
            output.write(message);
            output.write('\n');
        } catch (IOException error) {
            Log.e(tag, "Could not write fixture result", error);
        }
    }
}
