package edu.stevens.swe.research.parser.core.model;

public class TestCaseInfo {
    private final String className;
    private final String methodName;
    private final String absolutePath;

    public TestCaseInfo(String className, String methodName, String absolutePath) {
        this.className = className;
        this.methodName = methodName;
        this.absolutePath = absolutePath;
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getAbsolutePath() {
        return absolutePath;
    }

    @Override
    public String toString() {
        return "TestCaseInfo{" +
               "className=''''" + className + "''''" +
               ", methodName=''''" + methodName + "''''" +
               ", absolutePath=''''" + absolutePath + "''''" +
               '}';
    }
} 