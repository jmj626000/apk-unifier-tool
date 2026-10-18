package com.apkunifier.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 Android 二进制 XML（AXML）解析器。
 *
 * <p>只实现从 {@code AndroidManifest.xml} 根节点 {@code <manifest>} 提取属性所需的部分，
 * 足以拿到 package / versionCode / versionName / split 等关键信息。</p>
 */
public final class BinaryXmlParser {

    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;

    private static final int TYPE_REFERENCE = 0x01;
    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;

    private static final int NO_ENTRY = -1;

    private final ByteBuffer buf;
    private final int totalSize;
    private String[] strings = new String[0];

    private BinaryXmlParser(byte[] data) {
        if (data.length < 8) {
            throw new IllegalArgumentException("文件过小，不是合法的二进制 XML");
        }
        this.buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int type = u16(0);
        if (type != RES_XML_TYPE) {
            throw new IllegalArgumentException("不是合法的二进制 XML，文件头=0x" + Integer.toHexString(type));
        }
        this.totalSize = i32(4);
    }

    public static BinaryXmlParser parse(byte[] data) {
        return new BinaryXmlParser(data);
    }

    /** 返回根节点 {@code <manifest>} 的属性表（属性名 -&gt; 字符串值）。 */
    public Map<String, String> manifestAttributes() {
        int pos = 8; // 跳过 XML 文件头（ResChunk_header）
        while (pos + 8 <= totalSize && pos + 8 <= buf.capacity()) {
            int chunkType = u16(pos);
            int headerSize = u16(pos + 2);
            int chunkSize = i32(pos + 4);
            if (chunkSize <= 0 || pos + chunkSize > buf.capacity()) {
                break;
            }
            if (chunkType == RES_STRING_POOL_TYPE) {
                readStringPool(pos, headerSize);
            } else if (chunkType == RES_XML_START_ELEMENT_TYPE) {
                Map<String, String> attrs = readStartElement(pos, headerSize);
                if (attrs != null) {
                    return attrs;
                }
            }
            pos += chunkSize;
        }
        return new LinkedHashMap<>();
    }

    private void readStringPool(int chunkPos, int headerSize) {
        int stringCount = i32(chunkPos + 8);
        int flags = i32(chunkPos + 16);
        int stringsStart = i32(chunkPos + 20);
        boolean utf8 = (flags & 0x100) != 0;
        if (stringCount <= 0 || stringCount > 1_000_000) {
            strings = new String[0];
            return;
        }
        strings = new String[stringCount];
        int offsetsBase = chunkPos + headerSize;
        for (int i = 0; i < stringCount; i++) {
            int off = i32(offsetsBase + i * 4);
            strings[i] = readString(chunkPos + stringsStart + off, utf8);
        }
    }

    private String readString(int at, boolean utf8) {
        try {
            if (utf8) {
                int p = at;
                int len = u8(p++);
                if ((len & 0x80) != 0) {
                    len = ((len & 0x7f) << 8) | u8(p++);
                }
                int byteLen = u8(p++);
                if ((byteLen & 0x80) != 0) {
                    byteLen = ((byteLen & 0x7f) << 8) | u8(p++);
                }
                byte[] b = new byte[byteLen];
                buf.get(p, b);
                return new String(b, StandardCharsets.UTF_8);
            }
            int p = at;
            int len = u16(p);
            p += 2;
            if ((len & 0x8000) != 0) {
                len = ((len & 0x7fff) << 16) | u16(p);
                p += 2;
            }
            char[] chars = new char[len];
            for (int i = 0; i < len; i++) {
                chars[i] = (char) u16(p);
                p += 2;
            }
            return new String(chars);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private Map<String, String> readStartElement(int chunkPos, int headerSize) {
        int extPos = chunkPos + headerSize;
        String elementName = stringAt(i32(extPos + 4));
        if (!"manifest".equals(elementName)) {
            return null;
        }
        int attributeStart = u16(extPos + 8);
        int attributeSize = u16(extPos + 10);
        int attributeCount = u16(extPos + 12);
        if (attributeSize <= 0) {
            attributeSize = 20;
        }
        int attrBase = extPos + attributeStart;
        Map<String, String> attrs = new LinkedHashMap<>();
        for (int i = 0; i < attributeCount; i++) {
            int a = attrBase + i * attributeSize;
            String name = stringAt(i32(a + 4));
            if (name == null) {
                continue;
            }
            int rawValueIdx = i32(a + 8);
            int dataType = u8(a + 15);
            int data = i32(a + 16);
            attrs.put(name, resolveValue(rawValueIdx, dataType, data));
        }
        return attrs;
    }

    private String resolveValue(int rawValueIdx, int dataType, int data) {
        if (rawValueIdx != NO_ENTRY) {
            String s = stringAt(rawValueIdx);
            if (s != null) {
                return s;
            }
        }
        switch (dataType) {
            case TYPE_STRING:
                String s = stringAt(data);
                return s != null ? s : "";
            case TYPE_INT_DEC:
                return Integer.toString(data);
            case TYPE_INT_HEX:
                return "0x" + Integer.toHexString(data);
            case TYPE_REFERENCE:
                return "@0x" + Integer.toHexString(data);
            default:
                return Integer.toString(data);
        }
    }

    private String stringAt(int idx) {
        if (idx < 0 || idx >= strings.length) {
            return null;
        }
        return strings[idx];
    }

    private int u8(int index) {
        return buf.get(index) & 0xFF;
    }

    private int u16(int index) {
        return buf.getShort(index) & 0xFFFF;
    }

    private int i32(int index) {
        return buf.getInt(index);
    }
}
