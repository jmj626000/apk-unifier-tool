package com.apkunifier.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** 依据扩展名 + ZIP 内容识别输入容器的真实格式。 */
public final class FormatDetector {

    private FormatDetector() {
    }

    public static ContainerFormat detect(Path file) throws IOException {
        ContainerFormat byExt = byExtension(file);
        ContainerFormat byContent = byContent(file);
        if (byContent == ContainerFormat.UNKNOWN) {
            return byExt;
        }
        if (byExt == ContainerFormat.UNKNOWN) {
            return byContent;
        }
        // 扩展名标称是普通 APK，但内容含多个 APK / 清单元数据 => 以内容为准
        if (byExt == ContainerFormat.APK && byContent.isBundle()) {
            return byContent;
        }
        return byExt;
    }

    private static ContainerFormat byExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xapk")) {
            return ContainerFormat.XAPK;
        }
        if (name.endsWith(".apkx")) {
            return ContainerFormat.APKX;
        }
        if (name.endsWith(".apkz")) {
            return ContainerFormat.APKZ;
        }
        if (name.endsWith(".apkm")) {
            return ContainerFormat.APKM;
        }
        if (name.endsWith(".apks")) {
            return ContainerFormat.APKS;
        }
        if (name.endsWith(".apk")) {
            return ContainerFormat.APK;
        }
        return ContainerFormat.UNKNOWN;
    }

    private static ContainerFormat byContent(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            boolean hasManifestJson = false;
            boolean hasInfoJson = false;
            boolean hasTocPb = false;
            boolean hasRootManifest = false;
            int apkCount = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".apk")) {
                    apkCount++;
                } else if (name.equals("androidmanifest.xml")) {
                    // 根目录下的清单 => 该文件本身就是 APK
                    hasRootManifest = true;
                } else if (name.equals("manifest.json")) {
                    hasManifestJson = true;
                } else if (name.equals("info.json")) {
                    hasInfoJson = true;
                } else if (name.equals("toc.pb")) {
                    hasTocPb = true;
                }
            }
            if (hasRootManifest) {
                return ContainerFormat.APK;
            }
            if (hasTocPb) {
                return ContainerFormat.APKS;
            }
            if (apkCount >= 1) {
                if (hasManifestJson) {
                    return ContainerFormat.XAPK;
                }
                if (hasInfoJson) {
                    return ContainerFormat.APKM;
                }
                // 内部含 APK 但根目录没有 AndroidManifest.xml => 它是容器而非 APK 本体
                return ContainerFormat.APKX;
            }
            return ContainerFormat.UNKNOWN;
        } catch (ZipException e) {
            return ContainerFormat.UNKNOWN;
        }
    }
}
