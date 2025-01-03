package examples;

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaMethod;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static java.lang.System.exit;

public class QDoxScanner {

    // 构造源码路径
    private static String constructFilePath(String baseDir, String packageName) {
        return baseDir + File.separator + packageName.replace(".", File.separator);
    }

    // 递归遍历 java.base 模块下的所有包
    private static void scanSourceFiles(String basePath) throws Exception {
        // 遍历目录下的所有文件
        try (Stream<Path> paths = Files.walk(Paths.get(basePath))) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith("File.java"))  // 只处理 .java 文件
                    .limit(1)
                    .forEach(filePath -> {
                        try {
                            System.out.println("解析文件: " + filePath.toString());
                            parseJavaFile(filePath.toFile());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
        }
    }

    // 使用 QDox 解析 Java 文件，提取注释和方法内容
    private static void parseJavaFile(File javaFile) throws Exception {
        JavaProjectBuilder builder = new JavaProjectBuilder();
        builder.addSource(javaFile);

        // 遍历文件中的所有类
        for (JavaClass javaClass : builder.getClasses()) {
            if (!javaClass.isPublic() && javaClass.isAbstract()) {
                // we don't care about non-public or abstract classes
                continue;
            }
            System.out.println("类: " + javaClass.getFullyQualifiedName());

            // 遍历类中的所有方法
            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.isPublic()) { // we only care about public methods
                    continue;
                }
                System.out.println("  方法: " + method.getName());

                // 获取方法的 JavaDoc 注释
                if (method.getComment() != null) {
                    System.out.println("    注释: " + method.getComment());
                }

                System.out.println("    方法签名: " + method.getDeclarationSignature(false));
                System.out.println("    源码: " +  method.getSourceCode());
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // 源码的基路径，你需要将其设置为你系统中的 Java 源码路径
        String baseDir = "C:\\Users\\Administrator\\.jdks\\openjdk-17.0.2\\lib\\src\\java.base\\java\\io";

        // 递归扫描并解析 java.base 下的所有包和类
        scanSourceFiles(baseDir);
    }
}