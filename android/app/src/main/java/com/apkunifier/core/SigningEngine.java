package com.apkunifier.core;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * 使用 apksig 对 APK 进行 v1 / v2 / v3 签名，使合并产物可直接安装。
 *
 * <p>apksig 要求输入与输出不能是同一个文件，因此调用方需先把合并结果写到临时文件。</p>
 */
public final class SigningEngine {

    private static final String BUILTIN_KEYSTORE_RESOURCE = "/debug.keystore";
    private static final String BUILTIN_PASSWORD = "android";
    private static final String BUILTIN_ALIAS = "androiddebugkey";

    public void sign(Path inputApk, Path outputApk, SigningConfig config, ProgressListener listener)
            throws ConversionException {
        try {
            KeyMaterial material = loadKeyMaterial(config, listener);
            ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                    material.alias, material.privateKey, material.certificates).build();

            int minSdk = config.minSdk > 0 ? config.minSdk : 1;
            boolean v1 = minSdk < 24; // 面向 Android 7.0 以下才需要 v1
            ApkSigner signer = new ApkSigner.Builder(List.of(signerConfig))
                    .setInputApk(inputApk.toFile())
                    .setOutputApk(outputApk.toFile())
                    .setMinSdkVersion(minSdk)
                    .setV1SigningEnabled(v1)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .build();
            signer.sign();

            if (listener != null) {
                listener.onLog("签名完成：v1=" + v1 + " v2=true v3=true（minSdk=" + minSdk + "）");
            }
        } catch (ConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new ConversionException("签名失败：" + e.getMessage(), e);
        }
    }

    private KeyMaterial loadKeyMaterial(SigningConfig config, ProgressListener listener) throws Exception {
        if (config.keystore != null) {
            byte[] data = Files.readAllBytes(config.keystore);
            char[] password = config.password == null ? new char[0] : config.password.toCharArray();
            KeyStore keyStore = loadKeyStore(data, password);
            return extract(keyStore, config.alias, password);
        }
        if (listener != null) {
            listener.onLog("使用内置调试密钥进行签名。");
        }
        try (InputStream in = SigningEngine.class.getResourceAsStream(BUILTIN_KEYSTORE_RESOURCE)) {
            if (in == null) {
                throw new ConversionException("内置调试密钥缺失，无法签名");
            }
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, BUILTIN_PASSWORD.toCharArray());
            return extract(keyStore, BUILTIN_ALIAS, BUILTIN_PASSWORD.toCharArray());
        }
    }

    private static KeyStore loadKeyStore(byte[] data, char[] password) throws Exception {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new ByteArrayInputStream(data), password);
            return keyStore;
        } catch (Exception pkcs12Failure) {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(new ByteArrayInputStream(data), password);
            return keyStore;
        }
    }

    private static KeyMaterial extract(KeyStore keyStore, String alias, char[] password) throws Exception {
        if (alias == null || !keyStore.containsAlias(alias)) {
            throw new ConversionException("密钥库中找不到别名：" + alias);
        }
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, password);
        Certificate[] chain = keyStore.getCertificateChain(alias);
        if (privateKey == null || chain == null || chain.length == 0) {
            throw new ConversionException("无法读取私钥或证书链，请检查别名与密码");
        }
        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate certificate : chain) {
            if (certificate instanceof X509Certificate x509) {
                certificates.add(x509);
            }
        }
        if (certificates.isEmpty()) {
            throw new ConversionException("证书链中没有可用的 X.509 证书");
        }
        return new KeyMaterial(alias, privateKey, certificates);
    }

    private record KeyMaterial(String alias, PrivateKey privateKey, List<X509Certificate> certificates) {
    }
}
