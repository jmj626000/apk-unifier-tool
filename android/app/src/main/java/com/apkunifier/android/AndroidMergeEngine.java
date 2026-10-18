package com.apkunifier.android;

import com.apkunifier.core.ConversionException;
import com.apkunifier.core.ProgressListener;
import com.reandroid.apkeditor.merge.Merger;
import com.reandroid.apkeditor.merge.MergerOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Android 版合并引擎：不做 fork，直接进程内调用内置的 APKEditor。
 *
 * <p>APKEditor 只扫描输入目录的顶层文件，因此传入的目录必须直接包含所有 split APK。</p>
 */
public class AndroidMergeEngine {

    /**
     * 合并 split APK。
     *
     * @param apkDir    仅包含 APK 的目录（顶层直接放置）
     * @param baseApk   base APK，目录合并失败时作为回退输入
     * @param outputApk 输出文件
     */
    public void merge(Path apkDir, Path baseApk, Path outputApk, ProgressListener listener)
            throws ConversionException {
        try {
            Files.deleteIfExists(outputApk);
        } catch (IOException e) {
            throw new ConversionException("无法覆盖已存在的输出文件：" + e.getMessage(), e);
        }

        listener.onIndeterminate("正在合并 split APK（APKEditor）...");
        boolean ok = tryMerge(apkDir.toAbsolutePath().toFile(), outputApk.toAbsolutePath().toFile(), listener);
        if (!ok && baseApk != null) {
            listener.onLog("以目录作为输入合并失败，改用 base APK 重试...");
            ok = tryMerge(baseApk.toAbsolutePath().toFile(), outputApk.toAbsolutePath().toFile(), listener);
        }
        if (!ok || !Files.exists(outputApk)) {
            throw new ConversionException("合并失败（APKEditor），请查看日志了解原因");
        }
        listener.onLog("合并完成：" + outputApk.getFileName());
    }

    private boolean tryMerge(File input, File output, ProgressListener listener) {
        try {
            MergerOptions opts = new MergerOptions();
            opts.inputFile = input;
            opts.outputFile = output;
            opts.force = true;
            listener.onLog("APKEditor 合并输入：" + input.getName());
            new Merger(opts).runCommand();
            return output.exists() && output.length() > 0;
        } catch (Throwable t) {
            listener.onLog("合并异常：" + t);
            return false;
        }
    }
}
