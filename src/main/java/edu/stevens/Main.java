package edu.stevens;

import edu.stevens.parser.ResearchParser;
import edu.stevens.parser.model.TestCaseInfo;
import org.eclipse.jdt.core.dom.ASTParser;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        System.out.println("Testing parser-core library...");

        Path projectRoot = Paths.get("/Users/chenhaowei/Documents/github/commons-cli");

        ResearchParser facade = new ResearchParser();

        try {
            System.out.println("\nDetecting project configuration for: " + projectRoot);
            // Use the new helper method to get and print config details
            String configDetails = facade.getDetectedParserConfigAsString(projectRoot);
            System.out.println(configDetails);

            // Optionally, if you need the ParserConfig object itself for other checks:
            // ParserConfig detectedConfig = facade.getDetectedParserConfig(projectRoot);
            // if (detectedConfig == null) {
            //     System.out.println("Failed to detect ParserConfig object.");
            //     return;
            // }

            System.out.println("\nAttempting to retrieve and configure ASTParser for project: " + projectRoot);
            ASTParser parser = facade.getParser(projectRoot); // This will re-detect internally
            if (parser != null) {
                System.out.println("Successfully created ASTParser instance.");
            } else {
                System.out.println("Failed to create ASTParser.");
            }

            System.out.println("\nAttempting to find test cases in project: " + projectRoot);
            List<TestCaseInfo> testCases = facade.getTestCases(projectRoot);

            if (testCases.isEmpty()) {
                System.out.println("No test cases found in the project.");
            } else {
                System.out.println("Found " + testCases.size() + " test cases:");
                for (TestCaseInfo tc : testCases) {
                    System.out.println("  Class: " + tc.getClassName() +
                                       ", Method: " + tc.getMethodName() +
                                       ", Path: " + tc.getAbsolutePath());
                }
            }

        } catch (Exception e) {
            System.err.println("An error occurred during testing:");
            e.printStackTrace();
        }
    }
}