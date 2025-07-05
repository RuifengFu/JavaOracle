public class NonBMPCaseTest {
    public static void main(String[] args) {
        String nonBMP = "\uD834\uDD1E"; // MUSICAL SYMBOL G CLEF (U+1D11E)
        System.out.println(nonBMP);
        System.out.println(nonBMP.toLowerCase());
        if (nonBMP.toLowerCase() != nonBMP) {
            throw new RuntimeException("toLowerCase created new instance unnecessarily");
        }
    }
}