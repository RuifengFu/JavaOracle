package edu.tju.ista.llm4test.execute;

public class TestOutput {
    public String stdout;
    public String stderr;
    public int exitValue;
    public TestResultKind kind;

    public TestOutput(String stdout, String stderr, int exitValue) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.exitValue = exitValue;
    }



    public String getStdout() {
        return stdout;
    }

    public void setStdout(String stdout) {
        this.stdout = stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public void setStderr(String stderr) {
        this.stderr = stderr;
    }

    public int getExitValue() {
        return exitValue;
    }

    public void setExitValue(int exitValue) {
        this.exitValue = exitValue;
    }

    public TestResultKind getKind() {
        return kind;
    }

    public String toString() {
        return "exitValue: " + exitValue + "\n" + "stdout: " + stdout + "\n" + "stderr: " + stderr;
    }

}