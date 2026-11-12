// Co-created with GPT (OpenAI), 2026-11-12. See NOTICE.
// Run with app_process on a disposable cloud AVD, using the production JNI .so.
package com.dearded.spektralab;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

// These instance declarations match the Kotlin object's existing JNI names.
final class NativeEngine {
    native String lastError();
    native String deviceInfo();
    native long create(String resources, String temporary);
    native void destroy(long engine);
    native String capabilities(long engine);
    native long openBitmap(long engine, Bitmap image, String space, String params,
                           float headroom, int sourceWidth, int sourceHeight);
    native Bitmap beforeBitmap(long session);
    native Bitmap renderBitmap(long session, String tier);
    native Bitmap exportBitmap(long session, String tier);
    native Bitmap repack(long session, Bitmap reuse);
    native boolean setOutput(long session, String target, boolean exporting);
    native boolean writeJpeg444(Bitmap image, String path, int quality, byte[] icc);
    native long exportJpeg444(long session, String path, String tier, int quality, byte[] icc);
    native void release(long session);
}

public final class FeedbackAndroid {
    static native boolean fill(Bitmap image);
    static native long[] statistics(Bitmap image);
    static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    static Bitmap synthetic(int w, int h) {
        Bitmap image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        check(fill(image), "cannot fill synthetic Bitmap");
        return image;
    }

    static long inspect(Bitmap image, String name, PrintWriter csv, File out) throws Exception {
        check(image != null, name + " returned null");
        int w = image.getWidth(), h = image.getHeight();
        long[] stats = statistics(image);
        check(stats != null && stats.length == 4, name + " cannot inspect pixels");
        long black = stats[0], white = stats[1], alpha = stats[2], hash = stats[3];
        csv.printf("%s,%d,%d,%d,%d,%d,%d,%s%n", name, w, h, image.getRowBytes(),
                   black, white, alpha, Long.toUnsignedString(hash));
        csv.flush();
        System.out.printf("frame=%s shape=%dx%d black_rows=%d white_rows=%d alpha_errors=%d%n",
                          name, w, h, black, white, alpha);
        check(black == 0 && white == 0 && alpha == 0, name + " corrupted synthetic image rows");
        int sw = Math.min(512, w), sh = Math.max(1, (int)((long)h * sw / w));
        Bitmap sample = Bitmap.createScaledBitmap(image, sw, sh, true);
        try (FileOutputStream file = new FileOutputStream(new File(out, name + ".png"))) {
            check(sample.compress(Bitmap.CompressFormat.PNG, 100, file), "sample PNG failed");
        }
        if (sample != image) sample.recycle();
        return hash;
    }

    static void decode(File file, int w, int h, String name, PrintWriter csv, File out) throws Exception {
        Bitmap image = BitmapFactory.decodeFile(file.getAbsolutePath());
        check(image != null && image.getWidth() == w && image.getHeight() == h,
              name + " decoded shape mismatch");
        inspect(image, name, csv, out);
        image.recycle();
        System.out.println("jpeg=" + name + " bytes=" + file.length());
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 6, "expected LIB RESOURCES OUTPUT WIDTH HEIGHT codec|pipeline");
        long initialHeap = Runtime.getRuntime().maxMemory();
        // app_process does not perform ActivityThread's ordinary largeHeap setup.
        Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
        Object vm = vmRuntime.getMethod("getRuntime").invoke(null);
        vmRuntime.getMethod("clearGrowthLimit").invoke(vm);
        long actualHeap = Runtime.getRuntime().maxMemory();
        System.out.println("heap initial=" + initialHeap + " expanded=" + actualHeap);
        check(actualHeap >= 256L * 1024 * 1024, "insufficient diagnostic Java heap: " + actualHeap);
        System.load(args[0]);
        System.load(new File(args[0]).getParent() + "/libfeedback_fixture.so");
        NativeEngine nativeEngine = new NativeEngine();
        File out = new File(args[2]);
        check(out.isDirectory() || out.mkdirs(), "cannot create evidence directory");
        int w = Integer.parseInt(args[3]), h = Integer.parseInt(args[4]);
        check(w > 0 && h > 0 && (long)w * h <= 40000000, "invalid synthetic dimensions");
        check(args[5].equals("codec") || args[5].equals("pipeline"), "invalid mode");
        String environment = "sdk=" + Build.VERSION.SDK_INT + " release=" + Build.VERSION.RELEASE +
            " fingerprint=" + Build.FINGERPRINT + " abi=" + String.join(",", Build.SUPPORTED_ABIS) +
            " java_heap_initial=" + initialHeap + " java_heap_max=" + Runtime.getRuntime().maxMemory() +
            "\n" + nativeEngine.deviceInfo();
        Files.writeString(Path.of(out.toString(), "environment.txt"), environment);
        System.out.println(environment);
        try (PrintWriter csv = new PrintWriter(new File(out, "frames.csv"))) {
            csv.println("frame,w,h,row_bytes,black_rows,white_rows,alpha_errors,fnv64_argb_words");
            Bitmap input = synthetic(w, h);
            inspect(input, "input", csv, out);
            if (args[5].equals("codec")) {
                File ordinary = new File(out, "bitmap.jpg"), jpeg444 = new File(out, "native444.jpg");
                try (FileOutputStream file = new FileOutputStream(ordinary)) {
                    check(input.compress(Bitmap.CompressFormat.JPEG, 95, file), "Bitmap JPEG failed");
                }
                check(nativeEngine.writeJpeg444(input, jpeg444.toString(), 95, null), nativeEngine.lastError());
                input.recycle();
                decode(ordinary, w, h, "bitmap-jpeg", csv, out);
                decode(jpeg444, w, h, "native444-jpeg", csv, out);
            } else {
                long engine = nativeEngine.create(args[1], out.toString()), session = 0;
                check(engine != 0, nativeEngine.lastError());
                try {
                    Files.writeString(Path.of(out.toString(), "capabilities.json"), nativeEngine.capabilities(engine));
                    String params = "{\"film_stock\":\"kodak_ultramax_400\",\"print_stock\":\"kodak_ultra_endura\"," +
                        "\"input_color_space\":\"ProPhoto RGB\",\"input_cctf_decoding\":false," +
                        "\"output_color_space\":\"ProPhoto RGB\",\"output_cctf_encoding\":true," +
                        "\"auto_exposure\":true,\"auto_exposure_method\":\"balanced\"," +
                        "\"exposure_compensation_ev\":0.5,\"film_format_mm\":36,\"print_exposure\":1}";
                    session = nativeEngine.openBitmap(engine, input, "sRGB", params, 0, 7028, 4688);
                    check(session != 0, nativeEngine.lastError());
                    input.recycle();
                    Bitmap before = nativeEngine.beforeBitmap(session);
                    inspect(before, "before", csv, out); before.recycle();
                    for (String tier : new String[]{"live", "preview", "full"}) {
                        Bitmap rendered = nativeEngine.renderBitmap(session, tier);
                        check(rendered != null, tier + ": " + nativeEngine.lastError());
                        long hash = inspect(rendered, tier, csv, out);
                        Bitmap repacked = nativeEngine.repack(session, null);
                        check(repacked != null, nativeEngine.lastError());
                        check(hash == inspect(repacked, tier + "-repack", csv, out), "repack differs");
                        repacked.recycle(); rendered.recycle();
                    }
                    for (String target : new String[]{"sRGB", "Display P3"}) {
                        String suffix = target.equals("sRGB") ? "srgb" : "p3";
                        check(nativeEngine.setOutput(session, target, true), nativeEngine.lastError());
                        Bitmap exported = nativeEngine.exportBitmap(session, "full");
                        check(exported != null, nativeEngine.lastError());
                        exported.setColorSpace(ColorSpace.get(target.equals("sRGB") ?
                            ColorSpace.Named.SRGB : ColorSpace.Named.DISPLAY_P3));
                        inspect(exported, "export-bitmap-" + suffix, csv, out);
                        File ordinary = new File(out, "film-bitmap-" + suffix + ".jpg");
                        try (FileOutputStream file = new FileOutputStream(ordinary)) {
                            check(exported.compress(Bitmap.CompressFormat.JPEG, 95, file), "film Bitmap JPEG failed");
                        }
                        exported.recycle();
                        decode(ordinary, w, h, "film-bitmap-jpeg-" + suffix, csv, out);
                    }
                    check(nativeEngine.setOutput(session, "sRGB", true), nativeEngine.lastError());
                    File jpeg = new File(out, "film444.jpg");
                    byte[] srgbIcc = Files.readAllBytes(Path.of(args[1], "io", "sRGB.icc"));
                    check(srgbIcc.length > 0, "missing sRGB ICC for native JPEG control");
                    long shape = nativeEngine.exportJpeg444(session, jpeg.toString(), "full", 95, srgbIcc);
                    check(shape != 0 && (int)(shape >>> 32) == w && (int)shape == h,
                          "export shape: " + nativeEngine.lastError());
                    decode(jpeg, w, h, "film444-jpeg", csv, out);
                } finally {
                    if (session != 0) nativeEngine.release(session);
                    nativeEngine.destroy(engine);
                }
            }
            check(!csv.checkError(), "CSV write failed");
        }
        System.out.println("PASS android " + args[5] + " " + w + "x" + h);
    }
}
