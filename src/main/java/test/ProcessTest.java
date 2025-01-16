package test;

public class ProcessTest {
    public static void main(String[] args) {
        try {
            String jtregCommand = args[0];  // 输出: 你好
            Process process = Runtime.getRuntime().exec(jtregCommand);
            String stdout = new String(process.getInputStream().readAllBytes());
            System.out.println(stdout);
        } catch (Exception e) {
            e.printStackTrace();
            }
    }
}
