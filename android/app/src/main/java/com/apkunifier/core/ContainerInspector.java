package com.apkunifier.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 检查容器内容、解析元数据，并把容器解包为可合并的 APK 目录。 */
public final class ContainerInspector {

    private static final int BUFFER_SIZE = 1 << 16;

    private static final Pattern P_PACKAGE = Pattern.compile("\"package_name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern P_VERSION_NAME = Pattern.compile("\"version_name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern P_VERSION_CODE = Pattern.compile("\"version_code\"\\s*:\\s*\"?([0-9]+)\"?");
    private static final Pattern P_MIN_SDK = Pattern.compile("\"min_sdk_version\"\\s*:\\s*\"?([0-9]+)\"?");
    private static final Pattern P_APP_NAME = Pattern.compile("\"app_name\"\\s*:\\s*\"([^\"]*)\"");

    private ContainerInspector() {
    }

    /** 快速检查容器，用于界面列表预览。 */
    public static ContainerInfo inspect(Path file) throws IOException {
        ContainerInfo info = new ContainerInfo();
        info.file = file;
        info.format = FormatDetector.detect(file);

        if (info.format == ContainerFormat.APK) {
            // 输入本身就是 APK，直接读取其清单
            try {
                ApkInfo apk = ApkAnalyzer.analyze(file);
                info.packageName = apk.packageName;
                info.versionName = apk.versionName;
                info.versionCode = apk.versionCode;
                info.minSdk = apk.minSdk;
                info.appName = apk.label;
            } catch (IOException e) {
                info.unsupported = true;
                info.note = "无法解析 APK：" + e.getMessage();
            }
            return info;
        }

        String manifestJson = null;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".apk")) {
                    info.apks.add(new ZipEntryInfo(name, entry.getSize(), entry.getCompressedSize()));
                } else if (lower.endsWith(".obb")) {
                    info.obbs.add(new ZipEntryInfo(name, entry.getSize(), entry.getCompressedSize()));
                } else {
                    info.otherEntryCount++;
                    if (name.equalsIgnoreCase("manifest.json") && manifestJson == null) {
                        manifestJson = readEntryText(zip, entry);
                    }
                }
            }
        }

        if (manifestJson != null) {
            parseManifestJson(info, manifestJson);
        }

        // 容器没有随包清单时，从 base 候选 APK 中读取包名与版本
        if (info.packageName == null && !info.apks.isEmpty()) {
            String baseCandidate = guessBaseName(info.apks, null);
            if (baseCandidate != null) {
                Path temp = null;
                try {
                    temp = Files.createTempFile("apk-unifier-inspect-", ".apk");
                    extractEntry(file, baseCandidate, temp);
                    ApkInfo apk = ApkAnalyzer.analyze(temp);
                    info.packageName = apk.packageName;
                    info.versionName = apk.versionName;
                    info.versionCode = apk.versionCode;
                } catch (IOException ignored) {
                    // 预览失败不影响后续转换
                } finally {
                    if (temp != null) {
                        try {
                            Files.deleteIfExists(temp);
                        } catch (IOException ignored) {
                            // 忽略
                        }
                    }
                }
            }
        }

        if (info.apks.isEmpty() && info.packageName == null) {
            info.unsupported = true;
            info.note = info.format == ContainerFormat.APKM
                    ? "未发现可用的 APK（新版 APKM 可能已加密，暂不支持）"
                    : "容器内没有找到任何 APK 文件";
        }
        return info;
    }

    /** 解包容器，将 APK 平铺到 apkDir，其余文件按原路径还原到 workDir。 */
    public static ExtractionResult extract(Path file, Path workDir, ProgressListener listener)
            throws IOException, ConversionException {
        Path apkDir = workDir.resolve("apks");
        Files.createDirectories(apkDir);
        ExtractionResult result = new ExtractionResult(workDir, apkDir);

        try (ZipFile zip = new ZipFile(file.toFile())) {
            List<ZipEntry> entries = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory()) {
                    entries.add(e);
                }
            }
            long totalBytes = entries.stream().mapToLong(e -> Math.max(e.getSize(), 0)).sum();
            long copied = 0;
            for (ZipEntry entry : entries) {
                String entryName = entry.getName();
                if (entryName.contains("..")) {
                    throw new ConversionException("容器包含非法路径（疑似目录穿越）：" + entryName);
                }
                String lower = entryName.toLowerCase(Locale.ROOT);
                Path target;
                if (lower.endsWith(".apk")) {
                    target = apkDir.resolve(safeFileName(entryName));
                    result.apkFiles.add(target);
                } else {
                    target = workDir.resolve(entryName).normalize();
                    if (!target.startsWith(workDir)) {
                        throw new ConversionException("容器包含非法路径（疑似目录穿越）：" + entryName);
                    }
                    if (lower.endsWith(".obb")) {
                        result.obbFiles.add(target);
                        result.obbRelativePaths.add(entryName);
                    }
                }
                Files.createDirectories(target.getParent());
                copyEntry(zip, entry, target);
                copied += Math.max(entry.getSize(), 0);
                if (totalBytes > 0 && listener != null) {
                    listener.onProgress((int) Math.min(35, 10 + copied * 25 / totalBytes));
                }
            }
        }
        return result;
    }

    /** 依据命名惯例猜测 base APK 的文件名。 */
    public static String guessBaseName(List<ZipEntryInfo> apks, String packageName) {
        if (apks.isEmpty()) {
            return null;
        }
        String packageApk = packageName == null ? null : packageName + ".apk";
        String best = null;
        long bestSize = -1;
        String fallbackBest = null;
        long fallbackSize = -1;
        for (ZipEntryInfo a : apks) {
            String fileName = safeFileName(a.name);
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (packageApk != null && lower.equals(packageApk.toLowerCase(Locale.ROOT))) {
                return a.name;
            }
            if (lower.equals("base.apk")) {
                return a.name;
            }
            boolean looksLikeSplit = lower.startsWith("config.") || lower.startsWith("split_")
                    || lower.contains(".config.") || lower.startsWith("config");
            if (!looksLikeSplit && a.size > fallbackSize) {
                fallbackSize = a.size;
                fallbackBest = a.name;
            }
            if (a.size > bestSize) {
                bestSize = a.size;
                best = a.name;
            }
        }
        return fallbackBest != null ? fallbackBest : best;
    }

    private static void parseManifestJson(ContainerInfo info, String json) {
        info.packageName = match(P_PACKAGE, json, info.packageName);
        info.versionName = match(P_VERSION_NAME, json, info.versionName);
        String vc = match(P_VERSION_CODE, json, null);
        if (vc != null) {
            info.versionCode = Long.parseLong(vc);
        }
        String minSdk = match(P_MIN_SDK, json, null);
        if (minSdk != null) {
            info.minSdk = Integer.parseInt(minSdk);
        }
        info.appName = match(P_APP_NAME, json, info.appName);
    }

    private static String match(Pattern pattern, String text, String fallbackText) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : fallbackText;
    }

    private static String readEntryText(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void extractEntry(Path container, String entryName, Path target) throws IOException {
        try (ZipFile zip = new ZipFile(container.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("容器中找不到条目：" + entryName);
            }
            copyEntry(zip, entry, target);
        }
    }

    private static void copyEntry(ZipFile zip, ZipEntry entry, Path target) throws IOException {
        try (InputStream in = zip.getInputStream(entry);
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new IOException("写入失败 " + target.getFileName() + "：" + e.getMessage(), e);
        }
    }

    /** 只取文件名部分并做安全校验，避免解包时写到子目录或越界。 */
    public static String safeFileName(String entryName) {
        String normalized = entryName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (fileName.isEmpty() || fileName.equals(".") || fileName.equals("..")) {
            return "unnamed-" + Math.abs(entryName.hashCode());
        }
        return fileName;
    }
}
