public class Test {
    public static void main(String[] args) {
        StringBuffer sb = new StringBuffer();
        sb.append((StringBuffer) null);
        System.out.println(sb.toString()); // 输出"null"，而预期是抛出NullPointerException
    }
}