package examples;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipOutputStreamBehaviorTest {
    public static void main(String[] args) {
        try {
            // Create a failing output stream
            OutputStream failingStream = new OutputStream() {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    throw new IOException("Simulated failure");
                }

                @Override
                public void write(int b) throws IOException {}
            };

            ZipOutputStream zipOut = new ZipOutputStream(failingStream);
            byte[] data = new byte[100];

            // Test 1: putNextEntry should fail but may create internal state
            try {
                zipOut.putNextEntry(new ZipEntry("test.txt"));
                System.out.println("UNEXPECTED: putNextEntry succeeded");
            } catch (IOException e) {
                System.out.println("Expected: putNextEntry failed: " + e.getMessage());
            }

            // Test 2: If no active entry exists, this should throw IllegalStateException
            // If an entry was created despite the failure, it will throw IOException
            try {
                zipOut.write(data, 0, data.length);
                System.out.println("UNEXPECTED: write succeeded");
            } catch (IllegalStateException e) {
                System.out.println("Expected: No active entry exists");
            } catch (IOException e) {
                System.out.println("ISSUE FOUND: Active entry exists despite putNextEntry failure");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}