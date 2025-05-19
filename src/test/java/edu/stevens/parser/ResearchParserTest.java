package edu.stevens.parser;

import edu.stevens.parser.core.ASTProcessor;
import edu.stevens.parser.core.ParserConfig;
import edu.stevens.parser.model.TestCaseInfo;
import edu.stevens.parser.utils.BuildToolDetectorFactory;
import edu.stevens.parser.utils.exceptions.ProjectDetectionException;
import org.eclipse.jdt.core.dom.ASTParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ResearchParserTest {

    @Mock
    private ParserConfig mockParserConfig;

    @InjectMocks
    private ResearchParser researchParser;

    private Path mockProjectRoot;
    private Path commonsCliProjectPath;

    @BeforeEach
    void setUp() {
        mockProjectRoot = Paths.get("/fake/project/root");
        commonsCliProjectPath = Paths.get(System.getProperty("user.dir"), "build", "test-projects", "commons-cli");
    }

    private void assumeTestProjectIsCloned() {
        Assumptions.assumeTrue(
            Files.exists(commonsCliProjectPath) && Files.isDirectory(commonsCliProjectPath) && Files.exists(commonsCliProjectPath.resolve("pom.xml")),
            "Test project 'commons-cli' not found or not cloned properly at " + commonsCliProjectPath.toAbsolutePath() + ". Skipping test."
        );
    }

    // --- Tests for getParser() ---

    @Test
    void getParser_shouldReturnConfiguredParser_whenDetectionSucceedsWithRealProject() throws ProjectDetectionException {
        assumeTestProjectIsCloned();
        // Arrange
        try (MockedStatic<BuildToolDetectorFactory> mockedFactory = mockStatic(BuildToolDetectorFactory.class)) {
            mockedFactory.when(() -> BuildToolDetectorFactory.detect(commonsCliProjectPath)).thenReturn(mockParserConfig);
            
            when(mockParserConfig.getComplianceLevel()).thenReturn("1.8");
            when(mockParserConfig.getClasspathEntries()).thenReturn(new String[]{});
            when(mockParserConfig.getSourcepathEntries()).thenReturn(new String[]{"src/main/java", "src/test/java"});
            when(mockParserConfig.getEncodings()).thenReturn(new String[]{StandardCharsets.UTF_8.name()});

            ASTParser parser = researchParser.getParser(commonsCliProjectPath);

            mockedFactory.verify(() -> BuildToolDetectorFactory.detect(commonsCliProjectPath));
            assertNotNull(parser, "ASTParser should not be null when using commons-cli project");
        }
    }

    @Test
    void getParser_shouldThrowProjectDetectionException_whenDetectionFails() throws ProjectDetectionException {
        // Arrange
        try (MockedStatic<BuildToolDetectorFactory> mockedFactory = mockStatic(BuildToolDetectorFactory.class)) {
            mockedFactory.when(() -> BuildToolDetectorFactory.detect(mockProjectRoot))
                .thenThrow(new ProjectDetectionException("Detection failed"));

            // Act & Assert
            ProjectDetectionException exception = assertThrows(ProjectDetectionException.class, () -> {
                researchParser.getParser(mockProjectRoot);
            });
            assertEquals("Detection failed", exception.getMessage());
        }
    }

    // --- Tests for getDetectedParserConfig() ---
    @Test
    void getDetectedParserConfig_shouldReturnConfig_whenDetectionSucceeds() throws ProjectDetectionException {
        // Arrange
        try (MockedStatic<BuildToolDetectorFactory> mockedFactory = mockStatic(BuildToolDetectorFactory.class)) {
            mockedFactory.when(() -> BuildToolDetectorFactory.detect(mockProjectRoot)).thenReturn(mockParserConfig);
            ParserConfig actualConfig = researchParser.getDetectedParserConfig(mockProjectRoot);
            assertSame(mockParserConfig, actualConfig);
            mockedFactory.verify(() -> BuildToolDetectorFactory.detect(mockProjectRoot));
        }
    }

    @Test
    void getDetectedParserConfig_shouldThrowException_whenDetectionFails() throws ProjectDetectionException {
        // Arrange
        try (MockedStatic<BuildToolDetectorFactory> mockedFactory = mockStatic(BuildToolDetectorFactory.class)) {
            ProjectDetectionException expectedException = new ProjectDetectionException("Test detection error");
            mockedFactory.when(() -> BuildToolDetectorFactory.detect(mockProjectRoot)).thenThrow(expectedException);
            ProjectDetectionException actualException = assertThrows(ProjectDetectionException.class, () -> {
                researchParser.getDetectedParserConfig(mockProjectRoot);
            });
            assertSame(expectedException, actualException);
            mockedFactory.verify(() -> BuildToolDetectorFactory.detect(mockProjectRoot));
        }
    }

    // --- Tests for getDetectedParserConfigAsString() ---
    @Test
    void getDetectedParserConfigAsString_shouldReturnFormattedString_whenConfigIsValid() throws ProjectDetectionException {
        // Arrange
        try (MockedStatic<BuildToolDetectorFactory> mockedFactory = mockStatic(BuildToolDetectorFactory.class)) {
            when(mockParserConfig.getComplianceLevel()).thenReturn("11");
            when(mockParserConfig.getClasspathEntries()).thenReturn(new String[]{"cp1.jar", "cp2.jar"});
            when(mockParserConfig.getSourcepathEntries()).thenReturn(new String[]{"src/main/java", "src/test/java"});
            when(mockParserConfig.getEncodings()).thenReturn(new String[]{"UTF-8"});
            mockedFactory.when(() -> BuildToolDetectorFactory.detect(mockProjectRoot)).thenReturn(mockParserConfig);

            String result = researchParser.getDetectedParserConfigAsString(mockProjectRoot);

            assertTrue(result.contains("Compliance Level: 11"));
            assertTrue(result.contains("Classpath Entries (2):"));
            assertTrue(result.contains("Sourcepath Entries (2):"));
            assertTrue(result.contains("Encodings: [UTF-8]"));
        }
    }
    
    @Test
    void getDetectedParserConfigAsString_shouldThrowException_whenDetectionFails() throws ProjectDetectionException {
        // Arrange
         try (MockedStatic<BuildToolDetectorFactory> mockedFactory = mockStatic(BuildToolDetectorFactory.class)) {
            ProjectDetectionException expectedException = new ProjectDetectionException("Config detection failed");
            mockedFactory.when(() -> BuildToolDetectorFactory.detect(mockProjectRoot)).thenThrow(expectedException);
             assertThrows(ProjectDetectionException.class, () -> {
                researchParser.getDetectedParserConfigAsString(mockProjectRoot);
            });
        }
    }

    // --- Tests for getTestCases() ---

    @Test
    void getTestCases_shouldFallbackToDefaultAndFindTests_whenConfiguredTestPathIsEmptyOrInvalid() throws ProjectDetectionException, IOException {
        assumeTestProjectIsCloned();
        try (MockedStatic<BuildToolDetectorFactory> mockedFactory = mockStatic(BuildToolDetectorFactory.class)) {
            mockedFactory.when(() -> BuildToolDetectorFactory.detect(commonsCliProjectPath)).thenReturn(mockParserConfig);
            
            // Simulate a scenario where configured test source paths are invalid or empty but contain "test"
            when(mockParserConfig.getSourcepathEntries()).thenReturn(new String[]{"src/test/nonexistentOrEmptyTestDir"}); 
            
            // Configure for the ASTProcessor that will be created
            lenient().when(mockParserConfig.getComplianceLevel()).thenReturn("1.8");
            lenient().when(mockParserConfig.getClasspathEntries()).thenReturn(new String[]{});
            lenient().when(mockParserConfig.getEncodings()).thenReturn(new String[]{StandardCharsets.UTF_8.name()});

            List<TestCaseInfo> testCases = researchParser.getTestCases(commonsCliProjectPath);
            
            // Expect it to fallback to "src/test/java" in commons-cli and find tests there
            assertFalse(testCases.isEmpty(), "Test case list should not be empty; it should fallback to default src/test/java in commons-cli and find tests.");
        }
    }

    @Test
    void getTestCases_shouldFindTestsInCommonsCli() throws ProjectDetectionException, IOException {
        assumeTestProjectIsCloned();
        try (MockedStatic<BuildToolDetectorFactory> mockedFactory = mockStatic(BuildToolDetectorFactory.class)) {
            mockedFactory.when(() -> BuildToolDetectorFactory.detect(commonsCliProjectPath)).thenReturn(mockParserConfig);
            
            when(mockParserConfig.getSourcepathEntries()).thenReturn(new String[]{"src/test/java"});
            lenient().when(mockParserConfig.getComplianceLevel()).thenReturn("1.8");
            lenient().when(mockParserConfig.getClasspathEntries()).thenReturn(new String[]{});
            lenient().when(mockParserConfig.getEncodings()).thenReturn(new String[]{StandardCharsets.UTF_8.name()});

            List<TestCaseInfo> testCases = researchParser.getTestCases(commonsCliProjectPath);

            assertFalse(testCases.isEmpty(), "Should find test cases in commons-cli src/test/java.");
        }
    }

    // The following tests for IOException and ParsingException can be adapted to use the real project
    // by setting up mockParserConfig to point to a specific (potentially non-existent for IO or malformed for parsing) file path
    // within the cloned commons-cli structure, or by trying to parse a file that would cause such an error.
    // However, these might be harder to reliably set up with a real, external project without modifying it.
    // For now, these might be better as more controlled unit tests if we can simulate the file reading part differently,
    // or accept they might be less precise with a real project.
    // Let's comment them out for now as they require careful setup with the real project, or a refactor of ResearchParser
    // to inject a file reading mechanism.

    // @Test
    // void getTestCases_shouldHandleIOExceptionGracefully_withRealProject() throws ProjectDetectionException, IOException { ... }

    // @Test
    // void getTestCases_shouldHandleParsingExceptionGracefully_withRealProject() throws ProjectDetectionException, IOException { ... }


    // --- Placeholder for tests requiring real project structures ---
    // The tests below are now effectively implemented by the new tests using commonsCliProjectPath
    // if they test the same aspects (e.g., getParser with a Maven project, getTestCases with annotations).
    // We can remove these placeholders or adapt them if they cover different scenarios.

    // @Test
    // @Disabled("Requires a real project structure with specific Maven/Gradle setup for BuildToolDetectorFactory")
    // void getParser_withRealMavenProject_shouldConfigureCorrectly() { ... }

    // @Test
    // @Disabled("Requires a real project structure with specific test files for TestAstVisitor")
    // void getTestCases_withRealProjectAndVariousAnnotations_shouldIdentifyAllTests() { ... }
} 