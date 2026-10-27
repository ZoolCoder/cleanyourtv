package com.zoolcoder.tv.calendar;

import java.nio.charset.StandardCharsets;

/**
 * A QR code for the page address, so a phone can open it with its camera instead of typing it. Byte mode,
 * error correction level M, versions 1 to 10 (up to 213 bytes), following ISO/IEC 18004. No library: the
 * add-on keeps its zero dependencies.
 */
final class QrCode {
    private static final int MAX_VERSION = 10;
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    final int version, size, mask;
    private final boolean[][] modules, function;

    private QrCode(int version, byte[] data) {
        this.version = version;
        size = version * 4 + 17;
        modules = new boolean[size][size];
        function = new boolean[size][size];
        drawFunctionPatterns();
        drawCodewords(withEcc(data));
        mask = bestMask();
        applyMask(mask);
        drawFormatBits(mask);
    }

    /** The smallest code that holds the text, or null when it is too long. */
    static QrCode encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int version = 1; version <= MAX_VERSION; version++) {
            int capacity = dataCodewords(version) * 8, countBits = version < 10 ? 8 : 16;
            if (4 + countBits + bytes.length * 8 > capacity) continue;
            return new QrCode(version, codewords(bytes, countBits, capacity));
        }
        return null;
    }

    /** True for a dark module; x is the column, y the row. */
    boolean dark(int x, int y) {
        return modules[y][x];
    }

    private static byte[] codewords(byte[] bytes, int countBits, int capacity) {
        Bits bits = new Bits(capacity);
        bits.append(0b0100, 4);
        bits.append(bytes.length, countBits);
        for (byte b : bytes) bits.append(b & 0xFF, 8);
        bits.append(0, Math.min(4, capacity - bits.length));
        bits.append(0, (8 - bits.length % 8) % 8);
        for (int pad = 0xEC; bits.length < capacity; pad ^= 0xEC ^ 0x11) bits.append(pad, 8);
        return bits.bytes;
    }

    private static int rawModules(int version) {
        int result = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int align = version / 7 + 2;
            result -= (25 * align - 10) * align - 55;
            if (version >= 7) result -= 36;
        }
        return result;
    }

    private static int dataCodewords(int version) {
        return rawModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version];
    }

    /** Splits the data into blocks, adds each block's Reed-Solomon codewords, and interleaves them. */
    private byte[] withEcc(byte[] data) {
        int blocks = BLOCKS[version], eccLength = ECC_PER_BLOCK[version];
        int raw = rawModules(version) / 8;
        int shortBlocks = blocks - raw % blocks, shortLength = raw / blocks;
        byte[] divisor = divisor(eccLength);
        byte[][] all = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int dataLength = shortLength - eccLength + (i < shortBlocks ? 0 : 1);
            byte[] block = new byte[shortLength + 1];
            System.arraycopy(data, k, block, 0, dataLength);
            k += dataLength;
            byte[] ecc = remainder(block, dataLength, divisor);
            System.arraycopy(ecc, 0, block, shortLength + 1 - eccLength, eccLength);
            all[i] = block;
        }
        byte[] result = new byte[raw];
        int n = 0;
        for (int i = 0; i < shortLength + 1; i++) {
            for (int j = 0; j < blocks; j++) {
                // Short blocks have no codeword at the end of their data part.
                if (i != shortLength - eccLength || j >= shortBlocks) result[n++] = all[j][i];
            }
        }
        return result;
    }

    private static byte[] divisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) multiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    private static byte[] remainder(byte[] data, int length, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (int i = 0; i < length; i++) {
            int factor = (data[i] ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int j = 0; j < result.length; j++) result[j] ^= (byte) multiply(divisor[j] & 0xFF, factor);
        }
        return result;
    }

    /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] align = alignmentPositions();
        int last = align.length - 1;
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j <= last; j++) {
                boolean corner = (i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0);
                if (!corner) alignment(align[i], align[j]);
            }
        }
        drawFormatBits(0);
        if (version >= 7) drawVersion();
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int count = version / 7 + 2;
        int step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2;
        int[] result = new int[count];
        result[0] = 6;
        for (int i = count - 1, position = size - 7; i >= 1; i--, position -= step) result[i] = position;
        return result;
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int distance = Math.max(Math.abs(dx), Math.abs(dy));
                if (x + dx >= 0 && x + dx < size && y + dy >= 0 && y + dy < size) {
                    set(x + dx, y + dy, distance != 2 && distance != 4);
                }
            }
        }
    }

    private void alignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) set(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
    }

    /** Level M is 00 in the format field. */
    private void drawFormatBits(int mask) {
        int data = mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private void drawVersion() {
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            int a = size - 11 + i % 3, b = i / 3;
            set(a, b, bit(bits, i));
            set(b, a, bit(bits, i));
        }
    }

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vertical = 0; vertical < size; vertical++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vertical : vertical;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!function[y][x] && masked(mask, x, y)) modules[y][x] = !modules[y][x];
            }
        }
    }

    private static boolean masked(int mask, int x, int y) {
        switch (mask) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (x / 3 + y / 2) % 2 == 0;
            case 5: return x * y % 2 + x * y % 3 == 0;
            case 6: return (x * y % 2 + x * y % 3) % 2 == 0;
            default: return ((x + y) % 2 + x * y % 3) % 2 == 0;
        }
    }

    /** The mask with the lowest penalty for runs, 2x2 blocks and dark balance. */
    private int bestMask() {
        int best = 0;
        long lowest = Long.MAX_VALUE;
        for (int candidate = 0; candidate < 8; candidate++) {
            applyMask(candidate);
            drawFormatBits(candidate);
            long penalty = penalty();
            if (penalty < lowest) {
                lowest = penalty;
                best = candidate;
            }
            applyMask(candidate);
        }
        return best;
    }

    private long penalty() {
        long result = 0;
        int dark = 0;
        for (int a = 0; a < size; a++) {
            int rowRun = 1, columnRun = 1;
            for (int b = 1; b < size; b++) {
                rowRun = modules[a][b] == modules[a][b - 1] ? rowRun + 1 : 1;
                columnRun = modules[b][a] == modules[b - 1][a] ? columnRun + 1 : 1;
                if (rowRun == 5) result += 3;
                else if (rowRun > 5) result++;
                if (columnRun == 5) result += 3;
                else if (columnRun > 5) result++;
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) dark++;
                if (x + 1 < size && y + 1 < size) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return result + Math.max(0, k) * 10L;
    }

    private static boolean bit(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    /** A fixed-size bit buffer, most significant bit first. */
    private static final class Bits {
        final byte[] bytes;
        int length;

        Bits(int capacity) {
            bytes = new byte[capacity / 8];
        }

        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--, length++) {
                if (bit(value, i)) bytes[length >>> 3] |= (byte) (0x80 >>> (length & 7));
            }
        }
    }
}
