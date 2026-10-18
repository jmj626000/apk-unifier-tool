package com.apkunifier.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 容器检查结果（用于界面预览，不代表已解包）。 */
public class ContainerInfo {

    public Path file;
    public ContainerFormat format = ContainerFormat.UNKNOWN;

    public String packageName;
    public String versionName;
    public long versionCode = -1L;
    public int minSdk = -1;
    public String appName;

    public final List<ZipEntryInfo> apks = new ArrayList<>();
    public final List<ZipEntryInfo> obbs = new ArrayList<>();
    public int otherEntryCount;

    /** 容器被加密或结构无法解析（例如新版 APKMirror 的加密 apkm）。 */
    public boolean unsupported;
    public String note;

    public String fileName() {
        return file == null ? "" : file.getFileName().toString();
    }

    public String displayVersion() {
        if (versionName == null) {
            return versionCode > 0 ? String.valueOf(versionCode) : "";
        }
        return versionCode > 0 ? versionName + " (" + versionCode + ")" : versionName;
    }
}
