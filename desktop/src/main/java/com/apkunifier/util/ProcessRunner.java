package com.apkunifier.util;

import com.apkunifier.core.ConversionException;
import com.apkunifier.core.ProgressListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** 运行外部进程并把输出实时转发到日志回调。 */
public class ProcessRunner {

    /** 执行命令，返回退出码；标准输出/错误合并转发到 listener。 */
    public int run(List<String> command, ProgressListener listener) throws ConversionException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ConversionException("无法启动外部进程：" + e.getMessage(), e);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (listener != null && !line.isBlank()) {
                    listener.onLog(line);
                }
            }
        } catch (IOException ignored) {
            // 进程输出读取中断时忽略
        }
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ConversionException("合并进程被中断", e);
        }
    }

    /** 当前 JVM 对应的 java 可执行文件路径。 */
    public static String javaExecutable() {
        String home = System.getProperty("java.home");
        if (home != null) {
            Path bin = Path.of(home, "bin", isWindows() ? "java.exe" : "java");
            if (bin.toFile().isFile()) {
                return bin.toString();
            }
        }
        return "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
