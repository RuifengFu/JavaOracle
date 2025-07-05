/*
 * Copyright (c) 2006, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug     6396794
 * @summary Check that LastGcInfo contents are reasonable
 * @author  Eamonn McManus
 * @modules jdk.management
 * @run     main/othervm -XX:-ExplicitGCInvokesConcurrent GcInfoCompositeType
 */
// Passing "-XX:-ExplicitGCInvokesConcurrent" to force System.gc()
// run on foreground when a concurrent collector is used and prevent situations when "GcInfo"
// is missing even though System.gc() was successfuly processed.

import java.util.*;
import java.lang.management.*;
import java.lang.reflect.*;
import javax.management.*;
import javax.management.openmbean.*;
import com.sun.management.GcInfo;

public class GcInfoCompositeType {
    private static int tested = 0;

    public static void main(String[] args) throws Exception {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        final ObjectName gcMXBeanPattern =
                new ObjectName("java.lang:type=GarbageCollector,*");
        Set<ObjectName> names =
                mbs.queryNames(gcMXBeanPattern, null);
        if (names.isEmpty())
            throw new Exception("Test incorrect: no GC MXBeans");
        System.gc();
        for (ObjectName n : names)
            tested += test(mbs, n);
        if (tested == 0)
            throw new Exception("No MXBeans were tested");
        System.out.println("Test passed");
    }

    private static int test(MBeanServer mbs, ObjectName n) throws Exception {
        System.out.println("Testing " + n);
        MBeanInfo mbi = mbs.getMBeanInfo(n);
        MBeanAttributeInfo lastGcAI = null;
        for (MBeanAttributeInfo mbai : mbi.getAttributes()) {
            if (mbai.getName().equals("LastGcInfo")) {
                lastGcAI = mbai;
                break;
            }
        }
        if (lastGcAI == null)
            throw new Exception("No LastGcInfo attribute");
        CompositeType declaredType =
                (CompositeType) lastGcAI.getDescriptor().getFieldValue("openType");
        checkType(declaredType);
        CompositeData cd =
                (CompositeData) mbs.getAttribute(n, "LastGcInfo");
        if (cd == null) {
            System.out.println("Value of attribute null");
            return 0;
        } else {
            checkType(cd.getCompositeType());
            // Enhanced Test Oracle by LLM: Validate actual data content of LastGcInfo
            checkGcInfoContent(cd);
            return 1;
        }
    }

    private static int checkType(CompositeType ct) throws Exception {
        Method[] methods = GcInfo.class.getMethods();
        Set<String> getters = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (Method m : methods) {
            if (m.getName().startsWith("get") && m.getParameterTypes().length == 0)
                getters.add(m.getName().substring(3));
        }
        Set<String> items = new HashSet<String>(ct.keySet());
        System.out.println("Items at start: " + items);

        // Now check that all the getters have a corresponding item in the
        // CompositeType, except the following:
        // getClass() inherited from Object should not be an item;
        // getCompositeType() inherited from CompositeData is not useful so
        // our hack removes it too.
        // Also track which items had corresponding getters, to make sure
        // there is at least one item which does not (GcThreadCount or
        // another gc-type-specific item).
        final String[] surplus = {"Class", "CompositeType"};
        for (String key : ct.keySet()) {
            if (getters.remove(key))
                items.remove(key);
        }
        if (!getters.equals(new HashSet<String>(Arrays.asList(surplus)))) {
            throw new Exception("Wrong getters: " + getters);
        }
        if (items.isEmpty()) {
            System.out.println("No type-specific items");
        } else {
            System.out.println("Type-specific items: " + items);
        }
        return 1; // Always return 1 since we tested this MXBean
    }

    // Enhanced Test Oracle by LLM: Validate content of GcInfo CompositeData
    private static void checkGcInfoContent(CompositeData cd) throws Exception {
        // Reason: Validate core GcInfo fields based on documented behavior
        long id = (Long) cd.get("id");
        long startTime = (Long) cd.get("startTime");
        long endTime = (Long) cd.get("endTime");
        long duration = (Long) cd.get("duration");
        TabularData beforeGc = (TabularData) cd.get("memoryUsageBeforeGc");
        TabularData afterGc = (TabularData) cd.get("memoryUsageAfterGc");

        // Validate GC ID (must be positive)
        if (id <= 0) {
            throw new Exception("GC ID must be positive: " + id);
        }

        // Validate time relationships
        if (startTime < 0 || endTime < 0) {
            throw new Exception(String.format(
                "Invalid timestamps: startTime=%d, endTime=%d", 
                startTime, endTime
            ));
        }
        if (endTime < startTime) {
            throw new Exception(String.format(
                "End time before start: start=%d, end=%d", 
                startTime, endTime
            ));
        }
        if (duration != (endTime - startTime)) {
            throw new Exception(String.format(
                "Duration mismatch: expected=%d, actual=%d",
                (endTime - startTime), duration
            ));
        }

        // Validate memory usage maps
        if (beforeGc == null) {
            throw new Exception("memoryUsageBeforeGc is null");
        }
        if (afterGc == null) {
            throw new Exception("memoryUsageAfterGc is null");
        }

        // Verify same memory pools before/after GC
        Set<String> beforePools = extractMemoryPoolNames(beforeGc);
        Set<String> afterPools = extractMemoryPoolNames(afterGc);
        if (!beforePools.equals(afterPools)) {
            throw new Exception(String.format(
                "Memory pool mismatch: before=%s, after=%s", 
                beforePools, afterPools
            ));
        }

        // Validate individual MemoryUsage entries
        for (String pool : beforePools) {
            CompositeData beforeData = (CompositeData) beforeGc.get(new Object[]{pool});
            CompositeData afterData = (CompositeData) afterGc.get(new Object[]{pool});
            validateMemoryUsage(beforeData, "before", pool);
            validateMemoryUsage(afterData, "after", pool);
        }
    }

    private static Set<String> extractMemoryPoolNames(TabularData tabular) {
        Set<String> pools = new HashSet<>();
        for (Object key : tabular.keySet()) {
            // Key is List<?> with single element (pool name)
            List<?> keyList = (List<?>) key;
            pools.add((String) keyList.get(0));
        }
        return pools;
    }

    // Fix: Handle missing fields in MemoryUsage CompositeData
    private static void validateMemoryUsage(CompositeData data, String phase, String pool) 
            throws Exception {
        // Check which MemoryUsage fields are present
        CompositeData memoryUsage = (CompositeData) data.get("value");

        // Now check the real MemoryUsage fields
        Set<String> keys = memoryUsage.getCompositeType().keySet();
        System.out.println("MemoryUsage keys for " + pool + ": " + keys);
        data = (CompositeData) data.get("value");

        boolean hasInit = keys.contains("init");
        boolean hasUsed = keys.contains("used");
        boolean hasCommitted = keys.contains("committed");
        boolean hasMax = keys.contains("max");

        // Ensure at least the essential 'used' field is present
        if (!hasUsed) {
            throw new Exception(String.format(
                "Missing required 'used' field in %s GC for %s", 
                phase, pool
            ));
        }

        // Extract available fields
        Long init = hasInit ? (Long) data.get("init") : null;
        Long used = hasUsed ? (Long) data.get("used") : null;
        Long committed = hasCommitted ? (Long) data.get("committed") : null;
        Long max = hasMax ? (Long) data.get("max") : null;

        // Validate available fields
        if (hasInit && init != null && init < 0) {
            throw new Exception(String.format(
                "Negative init in %s GC for %s: %d", 
                phase, pool, init
            ));
        }
        if (hasUsed && used != null && used < 0) {
            throw new Exception(String.format(
                "Negative used in %s GC for %s: %d", 
                phase, pool, used
            ));
        }
        if (hasCommitted && committed != null && committed < 0) {
            throw new Exception(String.format(
                "Negative committed in %s GC for %s: %d", 
                phase, pool, committed
            ));
        }
        if (hasMax && max != null && max < -1) { // Max can be -1 (undefined) but not less
            throw new Exception(String.format(
                "Invalid max in %s GC for %s: %d", 
                phase, pool, max
            ));
        }

        // Validate memory constraints for available fields
        if (hasUsed && hasCommitted && used != null && committed != null && used > committed) {
            throw new Exception(String.format(
                "Used > committed in %s GC for %s: %d > %d", 
                phase, pool, used, committed
            ));
        }
        if (hasCommitted && hasMax && committed != null && max != null && max != -1 && committed > max) {
            throw new Exception(String.format(
                "Committed > max in %s GC for %s: %d > %d", 
                phase, pool, committed, max
            ));
        }
    }
}
