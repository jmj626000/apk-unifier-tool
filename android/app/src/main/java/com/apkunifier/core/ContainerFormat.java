package com.apkunifier.core;

/**
 * 支持的输入容器格式。
 *
 * <p>这些格式本质都是 ZIP 容器，内部包含一个 base APK 与若干 split APK（配置分包），
 * 部分格式还会附带 OBB 数据文件。</p>
 */
public enum ContainerFormat {

    XAPK("XAPK", "APKPure 等分发的安装包容器，含 manifest.json"),
    APKX("APKX", "多 APK 打包容器"),
    APKZ("APKZ", "多 APK 打包容器（国内分发常见）"),
    APKM("APKM", "APKMirror 分发的安装包容器，含 info.json"),
    APKS("APKS", "bundletool 生成的 split APK 集合，含 toc.pb"),
    APK("APK", "普通单个 APK（已可直接安装）"),
    UNKNOWN("未知", "无法识别的容器");

    private final String displayName;
    private final String description;

    ContainerFormat(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** 是否需要合并 split（普通 APK 与未知格式无需合并）。 */
    public boolean isBundle() {
        return this != APK && this != UNKNOWN;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
