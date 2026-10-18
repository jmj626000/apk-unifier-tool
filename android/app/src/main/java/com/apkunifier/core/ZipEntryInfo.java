package com.apkunifier.core;

/** ZIP 内单个条目的轻量信息。 */
public class ZipEntryInfo {

    public final String name;
    public final long size;
    public final long compressedSize;

    public ZipEntryInfo(String name, long size, long compressedSize) {
        this.name = name;
        this.size = size;
        this.compressedSize = compressedSize;
    }
}
