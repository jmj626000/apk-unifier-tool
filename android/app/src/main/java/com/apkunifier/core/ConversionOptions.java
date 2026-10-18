package com.apkunifier.core;

import java.nio.file.Path;

/** 转换选项。 */
public class ConversionOptions {

    /** 输出目录；为空时输出到源文件同目录。 */
    public Path outputDir;

    /** 手动指定的 APKEditor.jar 路径；为空时自动查找。 */
    public Path apkeditorJar;

    /** 是否把容器内的 OBB 一并还原到输出目录。 */
    public boolean extractObb = true;

    /** 输出文件已存在时是否覆盖。 */
    public boolean overwrite = true;

    /** 转换结束后是否保留临时解包目录（调试用）。 */
    public boolean keepWorkDir = false;

    /** 合并后是否自动签名，使产物可直接安装。 */
    public boolean sign = true;

    /** 签名密钥库路径；为空则使用内置调试密钥。 */
    public Path keystore;

    /** 密钥库口令。 */
    public String keystorePassword = "android";

    /** 密钥别名。 */
    public String keyAlias = "androiddebugkey";
}
