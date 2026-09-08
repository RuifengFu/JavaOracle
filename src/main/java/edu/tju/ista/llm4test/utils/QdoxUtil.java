package edu.tju.ista.llm4test.utils;

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;

/**
 * QDox 公共辅助：按 包名+类名（支持内部类）在builder中精确查找。
 * 不使用getClassByName（它对未知类会返回空壳占位对象，无法区分真实类）。
 */
public final class QdoxUtil {

    private QdoxUtil() {
    }

    /**
     * 在builder中查找类。
     * @param className 可含内部类链，如 "Map.Entry"
     * @return 真实存在的类；未找到返回null
     */
    public static JavaClass lookupClass(JavaProjectBuilder builder, String packageName, String className) {
        if (builder == null || className == null || className.isBlank()) {
            return null;
        }
        String outerName = className;
        String nestedPath = null;
        int dot = className.indexOf('.');
        if (dot != -1) {
            outerName = className.substring(0, dot);
            nestedPath = className.substring(dot + 1);
        }

        JavaClass found = null;
        for (JavaClass top : builder.getClasses()) {
            if (top.getName().equals(outerName)) {
                found = top;
                break;
            }
        }
        if (found == null) {
            return null;
        }
        if (nestedPath != null) {
            for (String part : nestedPath.split("\\.")) {
                JavaClass next = null;
                for (JavaClass nested : found.getNestedClasses()) {
                    if (nested.getName().equals(part)) {
                        next = nested;
                        break;
                    }
                }
                if (next == null) {
                    return null;
                }
                found = next;
            }
        }
        return found;
    }
}