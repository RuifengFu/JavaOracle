package edu.tju.ista.llm4test.javaparser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class APISignatureExtractor {

    private final CombinedTypeSolver typeSolver;

    public APISignatureExtractor() {
        this.typeSolver = new CombinedTypeSolver();
        this.typeSolver.add(new ReflectionTypeSolver()); // 添加Java标准库解析器
    }

    /**
     * 添加源代码路径用于解析
     * @param sourcePath 源代码根目录路径
     */
    public void addSourcePath(String sourcePath) {
        try {
            this.typeSolver.add(new JavaParserTypeSolver(new File(sourcePath)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to add source path: " + sourcePath, e);
        }
    }

    /**
     * 从单个Java文件中提取所有方法签名
     * @param filePath Java文件路径
     * @return 包含所有方法签名的Set
     */
    public Set<MethodSignature> extractSignatures(String filePath) {
        try {
            // 配置符号解析器
            JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
            StaticJavaParser.getParserConfiguration().setSymbolResolver(symbolSolver);
            StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

            // 解析Java文件
            File file = new File(filePath);
            CompilationUnit cu = StaticJavaParser.parse(file);

            // 存储找到的方法签名
            Set<MethodSignature> signatures = new HashSet<>();

            // 访问所有方法调用
            cu.findAll(MethodCallExpr.class).forEach(methodCall -> {
                try {
                    ResolvedMethodDeclaration resolvedMethod = methodCall.resolve();
                    signatures.add(new MethodSignature(
                            resolvedMethod.getQualifiedName(),
                            resolvedMethod.getPackageName(),
                            resolvedMethod.getClassName(),
                            resolvedMethod.getName(),
                            resolvedMethod.getReturnType().describe(),
                            resolvedMethod.getQualifiedSignature(),
                            methodCall.getBegin().get().line
                    ));
                } catch (Exception e) {
                    // 记录无法解析的方法调用
//                    signatures.add(new MethodSignature(
//                            methodCall.getNameAsString(),
//                            "unknown",
//                            "unknown",
//                            "unknown",
//                            methodCall.toString(),
//                            "unknown",
//                            methodCall.getBegin().get().line
//                    ));
//                    System.err.println("Failed to resolve method call: " + methodCall);
                }
            });

            // 访问所有构造函数调用
            cu.findAll(ObjectCreationExpr.class).forEach(objectCreation -> {
                try {
                    ResolvedConstructorDeclaration resolvedConstructor = objectCreation.resolve();
                    signatures.add(new MethodSignature(
                            resolvedConstructor.getQualifiedName(),
                            resolvedConstructor.getPackageName(),
                            resolvedConstructor.getClassName(),
                            resolvedConstructor.getName(),
                            resolvedConstructor.getClassName(), // 构造函数返回类型是类本身
                            resolvedConstructor.getQualifiedSignature(),
                            objectCreation.getBegin().get().line
                    ));
                } catch (Exception e) {
                    // 记录无法解析的构造函数调用
//                    System.err.println("Failed to resolve constructor call: " + objectCreation);
                }
            });
            var signs = signatures.stream().filter(sign -> !sign.getPackageName().isEmpty()).collect(Collectors.toSet()); // 去除本地api
            return signs;

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to extract signatures from file: " + filePath, e);
        }
    }

    /**
     * 从目录中递归提取所有Java文件的方法签名
     * @param directoryPath 目录路径
     * @return 包含所有方法签名的Set
     */
    public Set<MethodSignature> extractSignaturesFromDirectory(String directoryPath) {
        Set<MethodSignature> allSignatures = new HashSet<>();
        File directory = new File(directoryPath);

        if (!directory.exists() || !directory.isDirectory()) {
            throw new IllegalArgumentException("Invalid directory path: " + directoryPath);
        }

        processDirectory(directory, allSignatures);
        return allSignatures;
    }

    private void processDirectory(File directory, Set<MethodSignature> signatures) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    processDirectory(file, signatures);
                } else if (file.getName().endsWith(".java")) {
                    signatures.addAll(extractSignatures(file.getAbsolutePath()));
                }
            }
        }
    }

    /**
     * 方法签名数据类
     */
    public static class MethodSignature {
        private final String qualifiedName;    // 完整限定名
        private final String packageName;       // 包名
        private final String className;     // 类名
        private final String methodName;        // 方法名
        private final String returnType;       // 返回类型
        private final String signature;        // 完整签名
        private final int lineNumber;          // 行号

        public MethodSignature(String qualifiedName, String packageName, String className, String methodName, String returnType, String signature, int lineNumber) {
            this.qualifiedName = qualifiedName;
            this.packageName = packageName;
            this.className = className;
            this.methodName = methodName;
            this.returnType = returnType;
            this.signature = signature;
            this.lineNumber = lineNumber;
        }

        public String getQualifiedName() {
            return qualifiedName;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getClassName() {
            return className;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getReturnType() {
            return returnType;
        }

        public String getSignature() {
            return signature;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        @Override
        public String toString() {
            return String.format("%s %s (line %d)", returnType, signature, lineNumber);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MethodSignature that = (MethodSignature) o;
            return qualifiedName.equals(that.qualifiedName) &&
                    signature.equals(that.signature);
        }

        @Override
        public int hashCode() {
            return 31 * qualifiedName.hashCode() + signature.hashCode();
        }
    }
}