package assessment;

/** Open-addressing set of 64-bit path hashes: 8 bytes per slot instead of a 64-char String per visited path. */
final class VisitedPaths {
    long[] slots = new long[1024];
    int size;
    boolean zero;

    boolean contains(long key) {
        if (key == 0) return zero;
        int mask = slots.length - 1;
        for (int i = mix(key) & mask; slots[i] != 0; i = (i + 1) & mask) if (slots[i] == key) return true;
        return false;
    }
    /** True when the key was not present. */
    boolean add(long key) {
        if (key == 0) { if (zero) return false; zero = true; size++; return true; }
        if ((size + 1) * 4L > slots.length * 3L) grow();
        int mask = slots.length - 1, i = mix(key) & mask;
        for (; slots[i] != 0; i = (i + 1) & mask) if (slots[i] == key) return false;
        slots[i] = key; size++; return true;
    }
    int size() { return size; }
    void grow() {
        long[] old = slots; slots = new long[old.length * 2];
        int mask = slots.length - 1;
        for (long key : old) if (key != 0) { int i = mix(key) & mask; while (slots[i] != 0) i = (i + 1) & mask; slots[i] = key; }
    }
    static int mix(long key) { key ^= key >>> 33; key *= 0xff51afd7ed558ccdL; key ^= key >>> 33; return (int) key; }
}
