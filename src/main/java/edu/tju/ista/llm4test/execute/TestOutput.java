package edu.tju.ista.llm4test.execute;

public class TestOutput {
    public final String stdout;
    public final String stderr;
    public final int exitValue;
    public TestResultKind kind;

    public TestOutput(String stdout, String stderr, int exitValue) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.exitValue = exitValue;
    }



    public String getStdout() {
        return stdout;
    }



    public String getStderr() {
        return stderr;
    }



    public int getExitValue() {
        return exitValue;
    }


    public TestResultKind getKind() {
        return kind;
    }

    public String toString() {
        return "exitValue: " + exitValue + "\n" + "stdout: " + stdout + "\n" + "stderr: " + stderr;
    }

}