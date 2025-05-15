package edu.stevens.parser.utils;

import edu.stevens.parser.core.ParserConfig;
import edu.stevens.parser.utils.exceptions.ProjectDetectionException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MavenDetector extends AbstractBuildToolDetector {
    private static final String POM_FILE = "pom.xml";
    private static final String SETTINGS_FILE = "settings.xml";
    private static final String DEFAULT_M2_PATH = ".m2/repository";

    @Override
    public boolean supports(Path projectRoot) {
        return fileExists(projectRoot, POM_FILE);
    }

    @Override
    public ParserConfig detect(Path projectRoot) throws ProjectDetectionException {
        if (!supports(projectRoot)) {
            throw new ProjectDetectionException("Not a Maven project: " + projectRoot);
        }

        try {
            Path pomPath = projectRoot.resolve(POM_FILE);
            Document pomDoc = parseXmlFile(pomPath.toFile());
            
            // Start building the configuration
            ParserConfig.Builder configBuilder = createBaseConfig();

            // Add compiled classes directory (main)
            Path targetClasses = projectRoot.resolve("target/classes");
            if (targetClasses.toFile().exists()) {
                configBuilder.classpath(targetClasses.toString());
            }

            // Add compiled test classes directory
            Path targetTestClasses = projectRoot.resolve("target/test-classes");
            if (targetTestClasses.toFile().exists()) {
                configBuilder.classpath(targetTestClasses.toString());
            }

            // Add main resources directory
            Path mainResources = projectRoot.resolve("src/main/resources");
            if (mainResources.toFile().exists()) {
                configBuilder.classpath(mainResources.toString());
            }

            // Add test resources directory
            Path testResources = projectRoot.resolve("src/test/resources");
            if (testResources.toFile().exists()) {
                configBuilder.classpath(testResources.toString());
            }

            // Add main source directory
            Path mainSources = projectRoot.resolve("src/main/java");
            if (mainSources.toFile().exists()) {
                configBuilder.sourcepath(mainSources.toString());
            }

            // Add test source directory
            Path testSources = projectRoot.resolve("src/test/java");
            if (testSources.toFile().exists()) {
                configBuilder.sourcepath(testSources.toString());
            }

            // Add dependencies from Maven local repository
            addMavenDependencies(configBuilder, pomDoc);

            // Add JDK libs
            addJdkLibraries(configBuilder);

            return configBuilder.build();
        } catch (Exception e) {
            throw new ProjectDetectionException("Failed to detect Maven project configuration", e);
        }
    }

    private void addMavenDependencies(ParserConfig.Builder configBuilder, Document pomDoc) {
        // Get the local repository path
        Path localRepo = getMavenLocalRepository();
        
        // Parse dependencies from pom.xml
        NodeList dependencies = pomDoc.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dep = (Element) dependencies.item(i);
            String groupId = getElementContent(dep, "groupId");
            String artifactId = getElementContent(dep, "artifactId");
            String version = getElementContent(dep, "version");
            
            if (groupId != null && artifactId != null && version != null) {
                // Convert groupId to path format
                String groupPath = groupId.replace('.', '/');
                
                // Construct the path to the JAR file
                Path jarPath = localRepo.resolve(groupPath)
                        .resolve(artifactId)
                        .resolve(version)
                        .resolve(artifactId + "-" + version + ".jar");
                
                if (jarPath.toFile().exists()) {
                    configBuilder.classpath(jarPath.toString());
                }
            }
        }
    }

    private Path getMavenLocalRepository() {
        // First check M2_HOME environment variable
        String m2Home = System.getenv("M2_HOME");
        if (m2Home != null) {
            Path settingsPath = Paths.get(m2Home, "conf", SETTINGS_FILE);
            if (settingsPath.toFile().exists()) {
                try {
                    Document settingsDoc = parseXmlFile(settingsPath.toFile());
                    NodeList localRepoNodes = settingsDoc.getElementsByTagName("localRepository");
                    if (localRepoNodes.getLength() > 0) {
                        return Paths.get(localRepoNodes.item(0).getTextContent());
                    }
                } catch (Exception e) {
                    // Fall back to default
                }
            }
        }
        
        // Default to ~/.m2/repository
        return Paths.get(getUserHome(), DEFAULT_M2_PATH);
    }

    private void addJdkLibraries(ParserConfig.Builder configBuilder) {
        Path javaHome = Paths.get(getJavaHome());
        Path jrtFs = javaHome.resolve("lib/jrt-fs.jar");
        
        // For Java 8 and earlier
        Path rtJar = javaHome.resolve("lib/rt.jar");
        
        if (rtJar.toFile().exists()) {
            configBuilder.classpath(rtJar.toString());
        } else if (jrtFs.toFile().exists()) {
            configBuilder.classpath(jrtFs.toString());
        }
    }

    private Document parseXmlFile(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    private String getElementContent(Element parent, String tagName) {
        NodeList elements = parent.getElementsByTagName(tagName);
        if (elements.getLength() > 0) {
            return elements.item(0).getTextContent();
        }
        return null;
    }
}
