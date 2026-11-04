package com.rawr.gputest;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Debug;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import com.rawr.camera.integration.NativePreviewEngine;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Actual single-frame engine and durable writers while offscreen VF keeps running. */
final class NativeStillCapture {
    static JSONObject run(NativePreviewEngine engine, long handle, File dir, int cycle,
                          int profile, int demosaic, AtomicInteger frames, int pauseAfterMs) throws Exception {
        File dng = new File(dir, "capture-" + cycle + ".dng");
        File jpeg = new File(dir, "capture-" + cycle + ".jpg");
        long begin = SystemClock.elapsedRealtime();
        int firstFrame = frames.get();
        long request;
        int mode = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE |
                   ParcelFileDescriptor.MODE_TRUNCATE;
        try (ParcelFileDescriptor d = ParcelFileDescriptor.open(dng, mode);
             ParcelFileDescriptor j = ParcelFileDescriptor.open(jpeg, mode)) {
            request = engine.requestRawStillCapture(handle, d.detachFd(), j.detachFd(), 98, 1,
                false, profile, "", "headless", demosaic, true, 20f, 2, true, true,
                0, 0, 0, "RAWR", "headless", dng.getName(), jpeg.getName(), false, null,
                true, .5f, .05f, .02f, new int[]{0, 5}, new float[]{0, 0, 0},
                "{\"captureSharpeningEnabled\":true,\"captureSharpeningLevel\":\"Low\"}",
                null, null, null, false, true, 95, 0, null);
        }
        assertTrue("Capture rejected", request > 0);
        String dngResult = "", jpegResult = "";
        long dngMs = 0, jpegMs = 0, peakPss = Debug.getPss(), nextMemory = begin;
        long pauseAt = 0;
        int drainedFrames = -1;
        while ((dngResult.isEmpty() || jpegResult.isEmpty()) && SystemClock.elapsedRealtime() - begin < 90_000) {
            if (pauseAfterMs > 0 && pauseAt == 0 && SystemClock.elapsedRealtime() - begin >= pauseAfterMs) {
                engine.setUiPresentationActive(handle, false);
                pauseAt = SystemClock.elapsedRealtime();
            }
            if (pauseAt != 0 && SystemClock.elapsedRealtime() - pauseAt >= 1000) {
                if (drainedFrames < 0) drainedFrames = frames.get();
                assertEquals("Preview continues after in-capture UI pause/drain", drainedFrames, frames.get());
            }
            engine.tickCameraSession(handle);
            if (dngResult.isEmpty()) {
                dngResult = engine.pollDngWriteCompletion(handle);
                if (!dngResult.isEmpty()) dngMs = SystemClock.elapsedRealtime() - begin;
            }
            if (jpegResult.isEmpty()) {
                jpegResult = engine.pollJpegWriteCompletion(handle);
                if (!jpegResult.isEmpty()) jpegMs = SystemClock.elapsedRealtime() - begin;
            }
            if (SystemClock.elapsedRealtime() >= nextMemory) {
                peakPss = Math.max(peakPss, Debug.getPss());
                nextMemory = SystemClock.elapsedRealtime() + 250;
            }
            if (dngResult.isEmpty() || jpegResult.isEmpty()) Thread.sleep(25);
        }
        JSONObject result = new JSONObject().put("requestId", request).put("demosaic", demosaic)
            .put("dngCompletion", dngResult).put("jpegCompletion", jpegResult)
            .put("dngDurableMs", dngMs).put("jpegDurableMs", jpegMs).put("sampledPeakPssKiB", peakPss)
            .put("previewFramesDuringCapture", frames.get() - firstFrame)
            .put("dngBytes", dng.length()).put("jpegBytes", jpeg.length());
        if (pauseAt != 0) {
            result.put("uiPauseAfterMs", pauseAt - begin)
                .put("uiPauseLateFrames", drainedFrames < 0 ? -1 : frames.get() - drainedFrames);
            int beforeResume = frames.get();
            engine.setUiPresentationActive(handle, true);
            long resume = SystemClock.elapsedRealtime();
            while (frames.get() - beforeResume < 3 && SystemClock.elapsedRealtime() - resume < 5000)
                Thread.sleep(25);
            result.put("uiResumeFrames", frames.get() - beforeResume);
            assertTrue("Preview did not resume after capture", frames.get() - beforeResume >= 3);
        }
        java.nio.file.Files.write(new File(dir, "capture-" + cycle + ".json").toPath(),
            result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue("DNG timed out/failed: " + result, dngResult.startsWith(request + "\t1\t"));
        assertTrue("JPEG timed out/failed: " + result, jpegResult.startsWith(request + "\t1\t"));
        assertTrue(dng.length() > 0 && jpeg.length() > 0);
        byte[] header = new byte[8192];
        try (java.io.FileInputStream input = new java.io.FileInputStream(jpeg)) {
            int count = input.read(header);
            assertTrue(count > 0);
            String markers = new String(header, 0, count, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue("UltraHDR silently fell back to SDR", markers.contains("MPF\u0000") &&
                markers.contains("urn:iso:std:iso:ts:21496:-1"));
        }
        Bitmap bitmap = BitmapFactory.decodeFile(jpeg.getAbsolutePath());
        assertNotNull("Saved JPEG cannot decode", bitmap);
        result.put("width", bitmap.getWidth()).put("height", bitmap.getHeight());
        bitmap.recycle();
        String diagnostics = new String(java.nio.file.Files.readAllBytes(
            new File(dir, "rawrcam_validation_current.txt").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("Capture sharpening was not exercised", diagnostics.contains(
            "CAPTURE_SHARPENING requestId=" + request + " iterations=2 recorded=1"));
        result.put("captureSharpeningIterations", 2);
        return result;
    }
}
