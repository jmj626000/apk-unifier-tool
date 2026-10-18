package com.apkunifier.core;

import java.nio.file.Path;

/**
 * 签名参数。
 *
 * <p>{@code keystore} 为空时使用内置的调试密钥（嵌入在程序中，
 * 与 Android 调试密钥同款：别名 {@code androiddebugkey}，口令 {@code android}）。</p>
 */
public class SigningConfig {

    public Path keystore;
    public String password = "android";
    public String alias = "androiddebugkey";
    public int minSdk = 1;
}
