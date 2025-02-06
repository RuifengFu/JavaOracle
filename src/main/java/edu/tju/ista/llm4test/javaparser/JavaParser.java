package edu.tju.ista.llm4test.javaparser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.nodeTypes.modifiers.NodeWithPublicModifier;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaMethod;



import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 使用 QDox 解析 Java 源码
 */
public class JavaParser {


    /**
     * 从代码字符串中提取主类名
     * @param sourceCode Java源代码字符串
     * @return 主类名，如果没找到返回null
     */
    public static String extractMainClassName(String sourceCode) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(sourceCode);

            // 方法1：获取第一个公共类（通常是主类）
            Optional<ClassOrInterfaceDeclaration> mainClass = cu.findFirst(ClassOrInterfaceDeclaration.class,
                    NodeWithPublicModifier::isPublic);

            if (mainClass.isPresent()) {
                return mainClass.get().getNameAsString();
            }

            // 方法2：如果没有public类，获取第一个类
            Optional<ClassOrInterfaceDeclaration> firstClass = cu.findFirst(ClassOrInterfaceDeclaration.class);
            return firstClass.map(NodeWithSimpleName::getNameAsString).orElse(null);

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse source code", e);
        }
    }

    // 使用 QDox 解析 Java 文件，提取注释和方法内容
    public static List<JavaMethod> fileToMethods(File javaFile) {
        JavaProjectBuilder builder = new JavaProjectBuilder();
        try {
            builder.addSource(javaFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        ArrayList<JavaMethod> methods = new ArrayList<>();
        // 遍历文件中的所有类
        for (JavaClass javaClass : builder.getClasses()) {
            if (!javaClass.isPublic() && javaClass.isAbstract()) {
                // we don't care about non-public or abstract classes
                continue;
            }
//            System.out.println("类: " + javaClass.getFullyQualifiedName());

            // 遍历类中的所有方法
            for (JavaMethod method : javaClass.getMethods()) {
                if (!method.isPublic()) { // we only care about public methods
                    continue;
                }
                methods.add(method);
//                System.out.println("  方法: " + method.getName());
//                // 获取方法的 JavaDoc 注释
//                if (method.getComment() != null) {
//                    System.out.println("    注释: " + method.getComment());
//                }
//                System.out.println("    方法签名: " + method.getDeclarationSignature(false));
//                System.out.println("    源码: " +  method.getSourceCode());
            }
        }
        return methods;
    }

}