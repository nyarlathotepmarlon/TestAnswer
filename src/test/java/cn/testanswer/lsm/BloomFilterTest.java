package cn.testanswer.lsm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BloomFilterTest {
    @Test
    void filterHasNoFalseNegativesAfterSerializationAndRejectsMostMissingKeys() throws Exception {
        BloomFilter filter = new BloomFilter(2000);
        for (int i = 0; i < 2000; i++) { filter.add("用户:" + i); }
        BloomFilter restored = new BloomFilter(filter.bytes());
        for (int i = 0; i < 2000; i++) { assertTrue(restored.mightContain("用户:" + i)); }
        int negatives = 0;
        for (int i = 2000; i < 4000; i++) {
            if (!restored.mightContain("用户:" + i)) { negatives++; }
        }
        assertTrue(negatives > 1900, "Expected to skip over 95% of these missing keys, got " + negatives);
    }
}
