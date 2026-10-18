package com.apkunifier.core;

import com.apkunifier.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 调用 APKEditor.jar 把 base + split APK 合并成单个可安装 APK。
 */
public class MergeEngine {

    private final Path apkeditorJar;

    public MergeEngine(Path apkeditorJar) {
        this.apkeditorJar = apkeditorJar;
    }

    public boolean isAvailable() {
        return apkeditorJar != null && Files.isRegularFile(apkeditorJar);
    }

    /**
     * 合并 split APK。
     *
     * @param apkDir    仅包含 APK 的目录
     * @param baseApk   base APK（作为回退输入）
     * @param outputApk 输出文件
     */
    public void merge(Path apkDir, Path baseApk, Path outputApk, ProgressListener listener)
            throws ConversionException {
        if (!isAvailable()) {
            throw new ConversionException("未找到 APKEditor.jar，无法合并 split APK");
        }
        try {
            Files.deleteIfExists(outputApk);
        } catch (IOException e) {
            throw new ConversionException("无法覆盖已存在的输出文件：" + e.getMessage(), e);
        }

        listener.onIndeterminate("正在合并 split APK（APKEditor）...");
        int code = run(Arrays.asList("m", "-i", apkDir.toAbsolutePath().toString(),
                "-o", outputApk.toAbsolutePath().toString()), listener);
        if (code != 0 || !Files.exists(outputApk)) {
            if (baseApk != null) {
                listener.onLog("以目录作为输入合并失败（退出码 " + code + "），改用 base APK 重试...");
                code = run(Arrays.asList("m", "-i", baseApk.toAbsolutePath().toString(),
                        "-o", outputApk.toAbsolutePath().toString()), listener);
            }
        }
        if (code != 0 || !Files.exists(outputApk)) {
            throw new ConversionException("合并失败（APKEditor 退出码 " + code + "），请查看下方日志了解原因");
        }
    }

    private int run(List<String> args, ProgressListener listener) throws ConversionException {
        List<String> command = new ArrayList<>();
        command.add(ProcessRunner.javaExecutable());
        command.add("-jar");
        command.add(apkeditorJar.toAbsolutePath().toString());
        command.addAll(args);
        listener.onLog("$ " + String.join(" ", command));
        return new ProcessRunner().run(command, listener);
    }
}
