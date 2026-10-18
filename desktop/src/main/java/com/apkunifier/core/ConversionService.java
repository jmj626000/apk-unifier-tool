package com.apkunifier.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 转换编排：识别 → 解包 → 分析 → 合并 → 输出。 */
public class ConversionService {

    private final ToolManager toolManager = new ToolManager();

    public ToolManager toolManager() {
        return toolManager;
    }

    public ConversionResult convert(Path input, ConversionOptions options, ProgressListener listener)
            throws ConversionException {
        ProgressListener log = listener == null ? ProgressListener.NOOP : listener;
        Path workDir = null;
        try {
            log.onLog("=== 处理：" + input.getFileName());
            log.onProgress(2);

            ContainerInfo info = ContainerInspector.inspect(input);
            log.onLog("识别格式：" + info.format.displayName());
            if (info.format == ContainerFormat.APK) {
                return convertPlainApk(input, options, log);
            }
            if (info.unsupported) {
                throw new ConversionException(info.note != null ? info.note : "无法解析该容器");
            }

            workDir = Files.createTempDirectory("apk-unifier-");
            log.onProgress(8);
            ExtractionResult extracted = ContainerInspector.extract(input, workDir, log);
            log.onLog("解包完成：APK " + extracted.apkFiles.size()
                    + " 个，OBB " + extracted.obbFiles.size() + " 个");
            if (extracted.apkFiles.isEmpty()) {
                throw new ConversionException("容器内没有找到任何 APK 文件");
            }

            log.onProgress(38);
            ApkInfo base = findBaseApk(extracted.apkFiles);
            int splitCount = Math.max(0, extracted.apkFiles.size() - 1);
            log.onLog("base：" + base.fileName()
                    + "，包名：" + (base.packageName == null ? "未知" : base.packageName)
                    + "，版本：" + base.versionName + "(" + base.versionCode + ")"
                    + "，split：" + splitCount);
            log.onProgress(45);

            Path outputDir = options.outputDir != null ? options.outputDir
                    : input.toAbsolutePath().getParent();
            Files.createDirectories(outputDir);
            Path outputApk = outputDir.resolve(buildOutputName(base, input));
            if (Files.exists(outputApk) && !options.overwrite) {
                throw new ConversionException("输出文件已存在：" + outputApk.getFileName());
            }

            ConversionResult result = new ConversionResult();
            result.outputApk = outputApk;
            result.packageName = base.packageName;
            result.versionName = base.versionName;
            result.versionCode = base.versionCode;
            result.splitCount = splitCount;

            if (extracted.apkFiles.size() == 1) {
                // 单个 APK，无需合并，直接复制
                log.onLog("输入仅包含单个 APK，直接复制到输出。");
                Files.copy(base.path, outputApk, StandardCopyOption.REPLACE_EXISTING);
                result.merged = false;
                log.onProgress(85);
            } else {
                Path tool = toolManager.locate(options.apkeditorJar);
                if (tool == null) {
                    throw new ConversionException(
                            "未找到 APKEditor.jar。请在界面中选择它的路径，或点击「自动下载」获取。");
                }
                log.onLog("使用合并工具：" + tool);
                Path merged = workDir.resolve("merged-unsigned.apk");
                new MergeEngine(tool).merge(extracted.apkDir, base.path, merged, log);
                result.merged = true;
                log.onProgress(82);

                if (options.sign) {
                    log.onLog("正在对合并结果签名…");
                    SigningConfig signing = new SigningConfig();
                    signing.keystore = options.keystore;
                    signing.password = options.keystorePassword;
                    signing.alias = options.keyAlias;
                    signing.minSdk = base.minSdk;
                    Path signed = workDir.resolve("merged-signed.apk");
                    new SigningEngine().sign(merged, signed, signing, log);
                    Files.copy(signed, outputApk, StandardCopyOption.REPLACE_EXISTING);
                    result.signed = true;
                } else {
                    Files.copy(merged, outputApk, StandardCopyOption.REPLACE_EXISTING);
                }
                log.onProgress(88);
            }

            if (!Files.exists(outputApk)) {
                throw new ConversionException("转换结束但未生成输出文件");
            }

            if (options.extractObb && !extracted.obbFiles.isEmpty()) {
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
            if (workDir != null) {
                if (options.keepWorkDir) {
                    log.onLog("临时目录已保留：" + workDir);
                } else {
                    deleteQuietly(workDir);
                }
            }
        }
    }

    /** 处理输入本身就是普通 APK 的情况：直接复制并按包名/版本重命名。 */
    private ConversionResult convertPlainApk(Path input, ConversionOptions options, ProgressListener log)
            throws ConversionException {
        try {
            ApkInfo base = ApkAnalyzer.analyze(input);
            log.onLog("包名：" + (base.packageName == null ? "未知" : base.packageName)
                    + "，版本：" + base.versionName + "(" + base.versionCode + ")");
            Path outputDir = options.outputDir != null ? options.outputDir
                    : input.toAbsolutePath().getParent();
            Files.createDirectories(outputDir);
            Path outputApk = outputDir.resolve(buildOutputName(base, input));
            if (Files.exists(outputApk) && !options.overwrite) {
                throw new ConversionException("输出文件已存在：" + outputApk.getFileName());
            }
            if (!outputApk.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize())) {
                Files.copy(input, outputApk, StandardCopyOption.REPLACE_EXISTING);
            } else {
                log.onLog("输出路径与输入一致，跳过复制。");
            }
            log.onProgress(100);
            ConversionResult result = new ConversionResult();
            result.outputApk = outputApk;
            result.packageName = base.packageName;
            result.versionName = base.versionName;
            result.versionCode = base.versionCode;
            result.merged = false;
            log.onLog("完成：" + result.summary());
            return result;
        } catch (IOException e) {
            throw new ConversionException("处理失败：" + e.getMessage(), e);
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

    /** 供界面展示：当前可用的合并工具路径（可能为 null）。 */
    public Path resolveTool(ConversionOptions options) {
        return toolManager.locate(options == null ? null : options.apkeditorJar);
    }

    /** 无副作用地检查若干输入文件，用于界面预览。 */
    public List<ContainerInfo> inspectAll(List<Path> files) {
        List<ContainerInfo> result = new ArrayList<>();
        for (Path file : files) {
            try {
                result.add(ContainerInspector.inspect(file));
            } catch (IOException e) {
                ContainerInfo failed = new ContainerInfo();
                failed.file = file;
                failed.format = ContainerFormat.UNKNOWN;
                failed.unsupported = true;
                failed.note = e.getMessage();
                result.add(failed);
            }
        }
        return result;
    }
}
