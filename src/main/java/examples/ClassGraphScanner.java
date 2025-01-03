package examples;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.MethodInfoList;
import io.github.classgraph.ModuleInfo;
import io.github.classgraph.ScanResult;


public class ClassGraphScanner {
    public static void main(String[] args) {
        try (ScanResult scanResult = new ClassGraph()
                .enableAllInfo()  // 启用所有类型信息的扫描
                .enableSystemJarsAndModules()
                .acceptModules("java.base")  // 扫描所有模块
                .scan()) {

            // 获取所有类
            scanResult.getAllClasses().filter(classInfo -> classInfo.getName().startsWith("java.io")).stream().limit(1).forEach(classInfo -> {
                System.out.println("类: " + classInfo.getName() + " " + classInfo.getSourceFile() + " " + classInfo.getMethodInfo().size());
                MethodInfoList methodInfoList = classInfo.getMethodInfo();
                methodInfoList.forEach(methodInfo -> {
                    System.out.println("方法: " + methodInfo);
                });
            });

//            // 获取所有接口
//            scanResult.getAllInterfaces().forEach(interfaceInfo -> {
//                System.out.println("接口: " + interfaceInfo.getName());
//            });
//
//            // 获取所有注解
//            scanResult.getAllAnnotations().forEach(annotationInfo -> {
//                System.out.println("注解: " + annotationInfo.getName());
//            });
        }
    }
}