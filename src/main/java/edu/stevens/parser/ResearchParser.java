package edu.stevens.parser;

import edu.stevens.parser.core.ASTProcessor;
import edu.stevens.parser.core.ParserConfig;
import edu.stevens.parser.model.TestCaseInfo;
import edu.stevens.parser.utils.BuildToolDetectorFactory;
import edu.stevens.parser.utils.exceptions.ProjectDetectionException;
import edu.stevens.parser.visitor.TestAstVisitor;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Facade class for the parser-core library.
 * Provides methods to obtain a configured ASTParser and to discover test cases
 * within a given Java project.
 */
public class ResearchParser {

    /**
     * Creates and configures an ASTParser for the given Java project root.
     * The parser is configured with appropriate classpath, sourcepath, and compliance level
     * based on the detected project type (e.g., Maven, Gradle).
     *
     * @param projectRoot The root directory of the Java project.
     * @return A configured {@link ASTParser} instance.
     * @throws ProjectDetectionException if the project type cannot be detected or configuration fails.
     */
    public ASTParser getParser(Path projectRoot) throws ProjectDetectionException {
        ParserConfig config = BuildToolDetectorFactory.detect(projectRoot);
        ASTProcessor processor = new ASTProcessor(config);
        return processor.createParser();
    }

    /**
     * Discovers all test cases within the specified Java project.
     * It identifies test source files, parses them, and uses an ASTVisitor
     * to find methods annotated with common test annotations (JUnit 4, JUnit 5, TestNG).
     *
     * @param projectRoot The root directory of the Java project.
     * @return A list of {@link TestCaseInfo} objects, each representing a found test case.
     *         Returns an empty list if no test cases are found or if test source directories cannot be identified.
     * @throws ProjectDetectionException if the project type cannot be detected or configuration fails.
     * @throws IOException if an error occurs while reading source files.
     */
    public List<TestCaseInfo> getTestCases(Path projectRoot) throws ProjectDetectionException, IOException {
        ParserConfig config = BuildToolDetectorFactory.detect(projectRoot);
        ASTProcessor astProcessor = new ASTProcessor(config);
        ASTParser parser = astProcessor.createParser();

        List<TestCaseInfo> allTestCases = new ArrayList<>();
        List<Path> testJavaFiles = new ArrayList<>();

        // Identify test source roots.
        String[] sourcePaths = config.getSourcepathEntries();
        if (sourcePaths != null) {
            for (String sourcePathStr : sourcePaths) {
                Path sourcePath = projectRoot.resolve(sourcePathStr); 
                if (Files.exists(sourcePath) && Files.isDirectory(sourcePath) && sourcePath.toString().contains("test")) {
                    try (Stream<Path> walk = Files.walk(sourcePath)) {
                        testJavaFiles.addAll(walk.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".java"))
                                .collect(Collectors.toList()));
                    }
                }
            }
        }
        if (testJavaFiles.isEmpty()) {
             Path defaultTestSourcePath = projectRoot.resolve("src/test/java");
             if (Files.exists(defaultTestSourcePath) && Files.isDirectory(defaultTestSourcePath)){
                 try (Stream<Path> walk = Files.walk(defaultTestSourcePath)) {
                        testJavaFiles.addAll(walk.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".java"))
                                .collect(Collectors.toList()));
                    }
             }
        }

        for (Path javaFile : testJavaFiles) {
            try {
                String content = new String(Files.readAllBytes(javaFile), StandardCharsets.UTF_8);
                parser.setSource(content.toCharArray());
                parser.setUnitName(javaFile.toAbsolutePath().toString()); 
                CompilationUnit cu = (CompilationUnit) parser.createAST(null);
                
                TestAstVisitor visitor = new TestAstVisitor(javaFile.toAbsolutePath().toString());
                cu.accept(visitor);
                allTestCases.addAll(visitor.getTestCases());
            } catch (IOException e) {
                System.err.println("Error reading file: " + javaFile + " - " + e.getMessage());
            } catch (Exception e) {
                System.err.println("Error parsing file: " + javaFile + " - " + e.getMessage());
            }
        }
        return allTestCases;
    }

    // --- Debug Helper Methods ---

    /**
     * Detects and returns the {@link ParserConfig} for the given project root.
     * This method is primarily for debugging and inspection purposes.
     *
     * @param projectRoot The root directory of the project.
     * @return The detected {@link ParserConfig}.
     * @throws ProjectDetectionException if detection fails.
     */
    public ParserConfig getDetectedParserConfig(Path projectRoot) throws ProjectDetectionException {
        return BuildToolDetectorFactory.detect(projectRoot);
    }

    /**
     * Detects the {@link ParserConfig} for the given project root and returns a formatted string representation.
     * This method is useful for debugging to quickly see the detected project configuration.
     *
     * @param projectRoot The root directory of the project.
     * @return A string detailing the {@link ParserConfig}.
     * @throws ProjectDetectionException if detection fails.
     */
    public String getDetectedParserConfigAsString(Path projectRoot) throws ProjectDetectionException {
        ParserConfig config = getDetectedParserConfig(projectRoot);
        if (config == null) {
            return "Failed to detect ParserConfig.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("ParserConfig Details:\n");
        sb.append("  Compliance Level: ").append(config.getComplianceLevel()).append("\n");
        
        sb.append("  Classpath Entries (").append(config.getClasspathEntries().length).append("):\n");
        for (String entry : config.getClasspathEntries()) {
            sb.append("    ").append(entry).append("\n");
        }
        
        sb.append("  Sourcepath Entries (").append(config.getSourcepathEntries().length).append("):\n");
        for (String entry : config.getSourcepathEntries()) {
            sb.append("    ").append(entry).append("\n");
        }

        sb.append("  Encodings: ").append(Arrays.toString(config.getEncodings())).append("\n");
        return sb.toString();
    }
} 