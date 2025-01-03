package edu.tju.ista.llm4test.utils;

import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;
import java.util.Map;
public class ConcurrentEnumCounter<E extends Enum<E>> {
    // 使用数组存储计数，这样性能最好
    private final AtomicInteger[] counts;
    private final Class<E> enumClass;

    public ConcurrentEnumCounter(Class<E> enumClass) {
        this.enumClass = enumClass;
        E[] enumConstants = enumClass.getEnumConstants();
        this.counts = new AtomicInteger[enumConstants.length];
        for (int i = 0; i < enumConstants.length; i++) {
            counts[i] = new AtomicInteger(0);
        }
    }

    public void increment(E enumValue) {
        counts[enumValue.ordinal()].incrementAndGet();
    }

    public void add(E enumValue, int delta) {
        counts[enumValue.ordinal()].addAndGet(delta);
    }

    public int get(E enumValue) {
        return counts[enumValue.ordinal()].get();
    }

    public void reset(E enumValue) {
        counts[enumValue.ordinal()].set(0);
    }

    public void resetAll() {
        for (AtomicInteger count : counts) {
            count.set(0);
        }
    }

    public Map<E, Integer> getCountMap() {
        Map<E, Integer> result = new EnumMap<>(enumClass);
        E[] enumConstants = enumClass.getEnumConstants();
        for (E enumValue : enumConstants) {
            result.put(enumValue, counts[enumValue.ordinal()].get());
        }
        return result;
    }

    // 获取总计数
    public long getTotalCount() {
        return Arrays.stream(counts)
                .mapToLong(AtomicInteger::get)
                .sum();
    }
}
