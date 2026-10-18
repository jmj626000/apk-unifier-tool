package com.apkunifier.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 读取 APK 内 AndroidManifest.xml，解析出包名、版本、split 属性。 */
public final class ApkAnalyzer {

    private ApkAnalyzer() {
    }

    public static ApkInfo analyze(Path apkFile) throws IOException {
        ApkInfo info = new ApkInfo();
        info.path = apkFile;
        try (ZipFile zip = new ZipFile(apkFile.toFile())) {
            ZipEntry manifest = zip.getEntry("AndroidManifest.xml");
            if (manifest == null) {
                throw new IOException("APK 缺少 AndroidManifest.xml：" + apkFile.getFileName());
            }
            byte[] data;
            try (InputStream in = zip.getInputStream(manifest)) {
                data = in.readAllBytes();
            }
            Map<String, String> attrs = BinaryXmlParser.parse(data).manifestAttributes();
            info.packageName = attrs.get("package");
            info.versionName = attrs.get("versionName");
            info.versionCode = parseLong(attrs.get("versionCode"));
            info.minSdk = parseInt(attrs.get("minSdkVersion"));
            info.label = attrs.get("label");
            String split = attrs.get("split");
            info.splitName = (split == null || split.isEmpty()) ? null : split;
            info.hasResources = zip.getEntry("resources.arsc") != null;
            info.hasDex = zip.stream().anyMatch(e -> e.getName().endsWith(".dex"));
            info.hasNativeLibs = zip.stream().anyMatch(e -> e.getName().startsWith("lib/"));
        }
        return info;
    }

    private static long parseLong(String value) {
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static int parseInt(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
