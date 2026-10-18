package com.apkunifier.android;

import com.apkunifier.core.ApkAnalyzer;
import com.apkunifier.core.ApkInfo;
import com.apkunifier.core.ContainerFormat;
import com.apkunifier.core.ContainerInfo;
import com.apkunifier.core.ContainerInspector;
import com.apkunifier.core.ConversionException;
import com.apkunifier.core.ConversionOptions;
import com.apkunifier.core.ConversionResult;
import com.apkunifier.core.ExtractionResult;
import com.apkunifier.core.ProgressListener;
import com.apkunifier.core.SigningConfig;
import com.apkunifier.core.SigningEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

/**
 * Android 版转换编排：识别 → 解包 →(可选)合并 → 签名 → 输出。
 *
 * <p>与桌面版 {@code ConversionService} 的区别：不依赖外部进程 / 工具下载，
 * 合并改用进程内的 {@link AndroidMergeEngine}，签名沿用内置调试密钥。</p>
 */
public class AndroidConversionService {

    private final AndroidMergeEngine mergeEngine = new AndroidMergeEngine();

    public ConversionResult convert(Path input, ConversionOptions options, ProgressListener listener)
            throws ConversionException {
        ProgressListener log = listener == null ? ProgressListener.NOOP : listener;
        Path workDir = null;
        try {
            log.onLog("=== 处理：" + input.getFileName());
            log.onProgress(2);

            ContainerInfo info = ContainerInspector.inspect(input);
            log.onLog("识别格式：" + info.format.displayName());
            if (info.unsupported) {
                throw new ConversionException(info.note != null ? info.note : "无法解析该容器");
            }

            workDir = Files.createTempDirectory("apk-unifier-");
            log.onProgress(8);

            List<Path> apkFiles;
            Path basePath;
            ExtractionResult extracted = null;
            if (info.format == ContainerFormat.APK) {
                apkFiles = List.of(input);
                basePath = input;
                log.onLog("输入为普通 APK，跳过解包。");
            } else {
                extracted = ContainerInspector.extract(input, workDir, log);
                log.onLog("解包完成：APK " + extracted.apkFiles.size()
                        + " 个，OBB " + extracted.obbFiles.size() + " 个");
                if (extracted.apkFiles.isEmpty()) {
                    throw new ConversionException("容器内没有找到任何 APK 文件");
                }
                apkFiles = extracted.apkFiles;
                basePath = findBaseApk(apkFiles).path;
            }

            log.onProgress(38);
            ApkInfo base = ApkAnalyzer.analyze(basePath);
            int splitCount = Math.max(0, apkFiles.size() - 1);
            log.onLog("包名：" + (base.packageName == null ? "未知" : base.packageName)
                    + "，版本：" + base.versionName + "(" + base.versionCode + ")"
                    + "，split：" + splitCount);
            log.onProgress(45);

            Path outputDir = options.outputDir != null
                    ? options.outputDir
                    : input.toAbsolutePath().getParent();
            Files.createDirectories(outputDir);
            Path outputApk = outputDir.resolve(buildOutputName(base, input));

            ConversionResult result = new ConversionResult();
            result.outputApk = outputApk;
            result.packageName = base.packageName;
            result.versionName = base.versionName;
            result.versionCode = base.versionCode;
            result.splitCount = splitCount;

            // 合并（或复制）出未签名产物
            Path unsigned = workDir.resolve("merged-unsigned.apk");
            if (apkFiles.size() > 1) {
                mergeEngine.merge(extracted.apkDir, basePath, unsigned, log);
                result.merged = true;
            } else {
                log.onLog("仅包含单个 APK，跳过合并。");
                Files.copy(basePath, unsigned, StandardCopyOption.REPLACE_EXISTING);
                result.merged = false;
            }
            log.onProgress(82);

            // 签名输出到最终路径（apksig 要求输入输出不同文件）
            if (options.sign) {
                log.onLog("正在对结果签名…");
                SigningConfig signing = new SigningConfig();
                signing.keystore = options.keystore;
                signing.password = options.keystorePassword;
                signing.alias = options.keyAlias;
                // 与桌面版一致：0 交由 SigningEngine 兜底为 1，确保 v1 签名开启、产物在旧设备也可安装
                signing.minSdk = base.minSdk;
                new SigningEngine().sign(unsigned, outputApk, signing, log);
                result.signed = true;
            } else {
                Files.copy(unsigned, outputApk, StandardCopyOption.REPLACE_EXISTING);
            }
            log.onProgress(88);

            if (!Files.exists(outputApk)) {
                throw new ConversionException("转换结束但未生成输出文件");
            }

            if (options.extractObb && extracted != null && !extracted.obbFiles.isEmpty()) {
                for (int i = 0; i < extracted.obbFiles.size(); i++) {
                    Path source = extracted.obbFiles.get(i);
                    String relative = extracted.obbRelativePaths.get(i);
                    Path target = outputDir.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    result.obbOutputs.add(target);
                    log.onLog("已还原 OBB：" + relative);
                }
            }

            log.onProgress(100);
            log.onLog("完成：" + result.summary());
            return result;
        } catch (IOException e) {
            throw new ConversionException("处理失败：" + e.getMessage(), e);
        } finally {
            if (workDir != null && !options.keepWorkDir) {
                deleteQuietly(workDir);
            }
        }
    }

    /** 从解包出的 APK 中找出 base APK（无 split 属性者；找不到则取最大者）。 */
    private ApkInfo findBaseApk(List<Path> apkFiles) throws IOException, ConversionException {
        ApkInfo base = null;
        ApkInfo largest = null;
        for (Path apk : apkFiles) {
            ApkInfo current = ApkAnalyzer.analyze(apk);
            if (base == null && current.isBase()) {
                base = current;
            }
            if (largest == null || size(apk) > size(largest.path)) {
                largest = current;
            }
        }
        if (base != null) {
            return base;
        }
        if (largest != null) {
            return largest;
        }
        throw new ConversionException("无法解析任何 APK 的清单信息");
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private String buildOutputName(ApkInfo base, Path input) {
        String packageName = base.packageName;
        String version = base.versionName != null && !base.versionName.isBlank()
                ? base.versionName
                : (base.versionCode > 0 ? String.valueOf(base.versionCode) : null);
        if (packageName == null || packageName.isBlank()) {
            String stem = input.getFileName().toString();
            int dot = stem.lastIndexOf('.');
            packageName = dot > 0 ? stem.substring(0, dot) : stem;
        }
        String name = version == null ? packageName + ".apk" : packageName + "_" + version + ".apk";
        return sanitizeFileName(name);
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private static void deleteQuietly(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 忽略清理失败
                }
            });
        } catch (IOException ignored) {
            // 目录可能已不存在
        }
    }
}
