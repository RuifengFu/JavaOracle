package edu.tju.ista.llm4test.execute;

public class TestCase {

    public String originFile;

    public String file;
    public TestResult result;

    public TestCase(String file){
        this.file = file;
        this.result = new TestResult(TestResultKind.UNKNOWN);
    }

    public void verifyTestFail() {

    }




}
