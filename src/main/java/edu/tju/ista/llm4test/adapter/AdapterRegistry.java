package edu.tju.ista.llm4test.adapter;

import edu.tju.ista.llm4test.adapter.jdk.JdkProjectAdapter;
import edu.tju.ista.llm4test.adapter.maven.MavenProjectAdapter;
import edu.tju.ista.llm4test.config.GlobalConfig;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 目标项目适配器注册表：按配置 {@code project.type} 构造并缓存适配器实例。
 * <p>
 * 当前支持：
 * <ul>
 *   <li>{@code jdk}（默认）—— JDK/jtreg 场景 {@link JdkProjectAdapter}</li>
 *   <li>{@code maven} —— 第三方 Maven 仓库（JUnit 测试增强）{@link MavenProjectAdapter}</li>
 * </ul>
 * 后续新增适配器在此注册即可，业务代码统一通过
 * {@link #get()} 获取，不感知具体实现。
 */
public final class AdapterRegistry {

    private static final ConcurrentHashMap<String, ProjectAdapter> CACHE = new ConcurrentHashMap<>();

    private AdapterRegistry() {
    }

    /**
     * 获取当前配置对应的适配器（进程内单例）
     */
    public static ProjectAdapter get() {
        return forType(GlobalConfig.getProjectType());
    }

    /**
     * 按类型获取适配器
     * @throws IllegalArgumentException 不支持的类型
     */
    public static ProjectAdapter forType(String type) {
        if (type == null || type.isBlank() || "jdk".equalsIgnoreCase(type.trim())) {
            return CACHE.computeIfAbsent("jdk", k -> new JdkProjectAdapter());
        }
        if ("maven".equalsIgnoreCase(type.trim())) {
            return CACHE.computeIfAbsent("maven", k -> new MavenProjectAdapter());
        }
        throw new IllegalArgumentException(
                "未支持的 project.type: " + type + "（当前支持: jdk, maven）");
    }
}