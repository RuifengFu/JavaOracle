import java.lang.invoke.ConstantBootstraps;

public class MinimalRepro {
    public static void main(String[] args) {
        try {
            // 预期：ClassCastException，实际：无异常或错误行为
            Object result = ConstantBootstraps.explicitCast(null, null, int.class, null);
            System.out.println("Unexpected success! Result: " + result);
        } catch (Throwable t) {
            System.out.println("Caught: " + t.getClass().getSimpleName());
        }
    }
}