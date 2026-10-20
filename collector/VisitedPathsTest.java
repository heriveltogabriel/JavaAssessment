package assessment;

import java.util.*;

public final class VisitedPathsTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        VisitedPaths set = new VisitedPaths();
        check(!set.contains(0) && set.add(0) && !set.add(0) && set.contains(0), "Zero key is a regular member");
        // Keys sharing a bucket in the initial table probe linearly.
        List<Long> bucket = new ArrayList<Long>();
        for (long key = 1; bucket.size() < 5; key++) if ((VisitedPaths.mix(key) & 1023) == 7) bucket.add(Long.valueOf(key));
        for (Long key : bucket) check(set.add(key.longValue()), "Colliding key inserted");
        for (Long key : bucket) check(set.contains(key.longValue()) && !set.add(key.longValue()), "Colliding key found once");
        check(!set.contains(bucket.get(4).longValue() + 1) || bucket.contains(Long.valueOf(bucket.get(4).longValue() + 1)), "Absent key in a collision run");
        Random random = new Random(42); long[] keys = new long[200000];
        for (int i = 0; i < keys.length; i++) { keys[i] = random.nextLong(); set.add(keys[i]); }
        check(set.slots.length > 1024 && set.size() <= set.slots.length * 3 / 4, "Table grows by rehash under the load factor");
        for (Long key : bucket) check(set.contains(key.longValue()), "Colliding keys survive rehash");
        for (long key : keys) check(set.contains(key), "Inserted key survives rehash");
        Random other = new Random(7); int false_positives = 0;
        for (int i = 0; i < 100000; i++) if (set.contains(other.nextLong())) false_positives++;
        check(false_positives == 0, "Absent keys are not reported");
        // Two budgets of 500,000 paths: the table stays well under the 96 MiB agent heap.
        VisitedPaths million = new VisitedPaths();
        for (int i = 0; i < 1000000; i++) million.add(random.nextLong());
        check(million.size() == 1000000 && million.slots.length * 8L <= 16L * 1024 * 1024, "1M keys fit in a 16 MiB table: " + million.slots.length);
        System.out.println("VISITED_PATHS_TESTS=PASS keys=" + million.size() + " tableBytes=" + million.slots.length * 8L);
    }
}
