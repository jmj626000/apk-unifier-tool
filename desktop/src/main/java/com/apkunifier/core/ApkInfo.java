package com.apkunifier.core;

import java.nio.file.Path;

/** 单个 APK 的元信息。 */
public class ApkInfo {

    public Path path;
    public String packageName;
    public String versionName;
    public long versionCode = -1L;
    public int minSdk = -1;
    public String label;

    /** split 名称；为 null 表示这是 base APK。 */
    public String splitName;

    public boolean hasResources;
    public boolean hasDex;
    public boolean hasNativeLibs;

    /** base APK 没有 split 属性。 */
    public boolean isBase() {
        return splitName == null || splitName.isEmpty();
    }

    public String fileName() {
        return path == null ? "" : path.getFileName().toString();
    }

    @Override
    public String toString() {
        return "ApkInfo{" + fileName()
                + ", pkg=" + packageName
                + ", split=" + (splitName == null ? "<base>" : splitName)
                + ", v=" + versionName + "(" + versionCode + ")"
                + '}';
    }
}
