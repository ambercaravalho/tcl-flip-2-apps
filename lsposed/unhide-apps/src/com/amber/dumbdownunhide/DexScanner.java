package com.amber.dumbdownunhide;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds the methods in an APK's dex files that load given string constants.
 * Obfuscation renames classes, methods and fields, but string literals such as
 * preference keys and tile IDs survive it, so they identify code across builds.
 */
final class DexScanner {

    /** A method that loads at least one of the anchor strings. */
    static final class Hit {
        /** Binary class name, e.g. {@code bc.f1}. */
        final String className;
        final String methodName;
        /** JVM method descriptor, e.g. {@code (Ljava/util/ArrayList;)Ljava/util/ArrayList;}. */
        final String descriptor;
        final Set<String> strings = new LinkedHashSet<>();

        Hit(String className, String methodName, String descriptor) {
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
        }

        @Override
        public String toString() {
            return className + "." + methodName + descriptor;
        }
    }

    /** Instruction widths in 16-bit code units, indexed by opcode. */
    private static final byte[] WIDTH = new byte[256];

    static {
        Arrays.fill(WIDTH, (byte) 1);
        for (int op : new int[] {0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f,
                0x20, 0x22, 0x23, 0x29, 0xfe, 0xff}) {
            WIDTH[op] = 2;
        }
        for (int op : new int[] {0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a,
                0x2b, 0x2c, 0xfc, 0xfd}) {
            WIDTH[op] = 3;
        }
        WIDTH[0x18] = 5;
        WIDTH[0xfa] = 4;
        WIDTH[0xfb] = 4;
        fill(0x2d, 0x3d, 2);
        fill(0x44, 0x6d, 2);
        fill(0x6e, 0x72, 3);
        fill(0x74, 0x78, 3);
        fill(0x90, 0xaf, 2);
        fill(0xd0, 0xe2, 2);
    }

    private static void fill(int from, int to, int width) {
        Arrays.fill(WIDTH, from, to + 1, (byte) width);
    }

    private static final int OP_CONST_STRING = 0x1a;
    private static final int OP_CONST_STRING_JUMBO = 0x1b;
    private static final int PACKED_SWITCH_PAYLOAD = 0x0100;
    private static final int SPARSE_SWITCH_PAYLOAD = 0x0200;
    private static final int FILL_ARRAY_DATA_PAYLOAD = 0x0300;

    private DexScanner() {}

    /** Returns every method in the APK that loads one of {@code anchors}, which must be ASCII. */
    static List<Hit> find(String apkPath, Set<String> anchors) throws IOException {
        List<Hit> hits = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("classes") && name.endsWith(".dex") && name.indexOf('/') < 0) {
                    scanDex(readAll(zip, entry), anchors, hits);
                }
            }
        }
        return hits;
    }

    private static byte[] readAll(ZipFile zip, ZipEntry entry) throws IOException {
        long size = entry.getSize();
        ByteArrayOutputStream out = new ByteArrayOutputStream(size > 0 ? (int) size : 1 << 20);
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static void scanDex(byte[] bytes, Set<String> anchors, List<Hit> hits) {
        ByteBuffer dex = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int stringIdsSize = dex.getInt(0x38);
        int stringIdsOff = dex.getInt(0x3c);
        int classDefsSize = dex.getInt(0x60);
        int classDefsOff = dex.getInt(0x64);

        Map<Integer, String> anchorIds = new HashMap<>();
        for (int i = 0; i < stringIdsSize; i++) {
            int dataOff = dex.getInt(stringIdsOff + 4 * i);
            for (String anchor : anchors) {
                if (stringEquals(dex, dataOff, anchor)) {
                    anchorIds.put(i, anchor);
                    break;
                }
            }
        }
        if (anchorIds.isEmpty()) {
            return;
        }

        int[] pos = new int[1];
        for (int c = 0; c < classDefsSize; c++) {
            int classDataOff = dex.getInt(classDefsOff + 32 * c + 24);
            if (classDataOff == 0) {
                continue;
            }
            pos[0] = classDataOff;
            int staticFields = uleb(dex, pos);
            int instanceFields = uleb(dex, pos);
            int directMethods = uleb(dex, pos);
            int virtualMethods = uleb(dex, pos);
            for (int i = 0; i < 2 * (staticFields + instanceFields); i++) {
                uleb(dex, pos);
            }
            int methodIdx = 0;
            for (int i = 0; i < directMethods + virtualMethods; i++) {
                if (i == directMethods) {
                    methodIdx = 0;
                }
                methodIdx += uleb(dex, pos);
                uleb(dex, pos); // access_flags
                int codeOff = uleb(dex, pos);
                if (codeOff == 0) {
                    continue;
                }
                Set<String> found = scanCode(dex, codeOff, anchorIds);
                if (found != null) {
                    Hit hit = describe(dex, methodIdx);
                    hit.strings.addAll(found);
                    hits.add(hit);
                }
            }
        }
    }

    private static Set<String> scanCode(ByteBuffer dex, int codeOff, Map<Integer, String> anchorIds) {
        int insnsSize = dex.getInt(codeOff + 12);
        int insns = codeOff + 16;
        Set<String> found = null;
        int pc = 0;
        while (pc < insnsSize) {
            int at = insns + 2 * pc;
            int unit = dex.getShort(at) & 0xffff;
            int op = unit & 0xff;
            int width;
            if (op == OP_CONST_STRING || op == OP_CONST_STRING_JUMBO) {
                int idx = op == OP_CONST_STRING ? dex.getShort(at + 2) & 0xffff : dex.getInt(at + 2);
                String anchor = anchorIds.get(idx);
                if (anchor != null) {
                    if (found == null) {
                        found = new LinkedHashSet<>();
                    }
                    found.add(anchor);
                }
                width = WIDTH[op];
            } else if (unit == PACKED_SWITCH_PAYLOAD) {
                width = (dex.getShort(at + 2) & 0xffff) * 2 + 4;
            } else if (unit == SPARSE_SWITCH_PAYLOAD) {
                width = (dex.getShort(at + 2) & 0xffff) * 4 + 2;
            } else if (unit == FILL_ARRAY_DATA_PAYLOAD) {
                long elementWidth = dex.getShort(at + 2) & 0xffff;
                long count = dex.getInt(at + 4) & 0xffffffffL;
                width = (int) ((count * elementWidth + 1) / 2 + 4);
            } else {
                width = WIDTH[op];
            }
            pc += width;
        }
        return found;
    }

    private static Hit describe(ByteBuffer dex, int methodIdx) {
        int typeIdsOff = dex.getInt(0x44);
        int protoIdsOff = dex.getInt(0x4c);
        int methodIdsOff = dex.getInt(0x5c);

        int method = methodIdsOff + 8 * methodIdx;
        int classIdx = dex.getShort(method) & 0xffff;
        int protoIdx = dex.getShort(method + 2) & 0xffff;
        int nameIdx = dex.getInt(method + 4);

        int proto = protoIdsOff + 12 * protoIdx;
        int returnTypeIdx = dex.getInt(proto + 4);
        int paramsOff = dex.getInt(proto + 8);
        StringBuilder descriptor = new StringBuilder("(");
        if (paramsOff != 0) {
            int count = dex.getInt(paramsOff);
            for (int i = 0; i < count; i++) {
                descriptor.append(typeName(dex, typeIdsOff, dex.getShort(paramsOff + 4 + 2 * i) & 0xffff));
            }
        }
        descriptor.append(')').append(typeName(dex, typeIdsOff, returnTypeIdx));

        String classDescriptor = typeName(dex, typeIdsOff, classIdx);
        String className = classDescriptor.substring(1, classDescriptor.length() - 1).replace('/', '.');
        return new Hit(className, string(dex, nameIdx), descriptor.toString());
    }

    private static String typeName(ByteBuffer dex, int typeIdsOff, int typeIdx) {
        return string(dex, dex.getInt(typeIdsOff + 4 * typeIdx));
    }

    private static String string(ByteBuffer dex, int stringIdx) {
        int[] pos = {dex.getInt(dex.getInt(0x3c) + 4 * stringIdx)};
        int utf16Length = uleb(dex, pos);
        StringBuilder sb = new StringBuilder(utf16Length);
        int p = pos[0];
        while (true) {
            int a = dex.get(p++) & 0xff;
            if (a == 0) {
                break;
            } else if (a < 0x80) {
                sb.append((char) a);
            } else if ((a & 0xe0) == 0xc0) {
                int b = dex.get(p++) & 0x3f;
                sb.append((char) (((a & 0x1f) << 6) | b));
            } else {
                int b = dex.get(p++) & 0x3f;
                int c = dex.get(p++) & 0x3f;
                sb.append((char) (((a & 0x0f) << 12) | (b << 6) | c));
            }
        }
        return sb.toString();
    }

    private static boolean stringEquals(ByteBuffer dex, int dataOff, String ascii) {
        int[] pos = {dataOff};
        if (uleb(dex, pos) != ascii.length()) {
            return false;
        }
        int p = pos[0];
        for (int i = 0; i < ascii.length(); i++) {
            if (dex.get(p + i) != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return dex.get(p + ascii.length()) == 0;
    }

    private static int uleb(ByteBuffer dex, int[] pos) {
        int result = 0;
        int shift = 0;
        int b;
        do {
            b = dex.get(pos[0]++) & 0xff;
            result |= (b & 0x7f) << shift;
            shift += 7;
        } while ((b & 0x80) != 0 && shift < 35);
        return result;
    }
}
