package edu.stevens.swe.research.java.parser.core.utils;

import edu.stevens.swe.research.java.parser.core.core.ParserConfig;
import edu.stevens.swe.research.java.parser.core.utils.exceptions.ProjectDetectionException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

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

            // Add Maven dependencies from Maven local repository
            Map<String, String> mavenProperties = parseProperties(pomDoc);
            addMavenDependencies(configBuilder, pomDoc, mavenProperties);

            // Add JDK libs
            addJdkLibraries(configBuilder);

            return configBuilder.build();
        } catch (Exception e) {
            throw new ProjectDetectionException("Failed to detect Maven project configuration", e);
        }
    }

    private Map<String, String> parseProperties(Document pomDoc) {
        Map<String, String> properties = new HashMap<>();
        NodeList propertyNodes = pomDoc.getElementsByTagName("properties");
        if (propertyNodes.getLength() > 0) {
            Node item = propertyNodes.item(0);
            if (item instanceof Element) {
                Element propertiesElement = (Element) item;
                NodeList childNodes = propertiesElement.getChildNodes();
                for (int i = 0; i < childNodes.getLength(); i++) {
                    Node childNode = childNodes.item(i);
                    if (childNode instanceof Element) {
                        Element property = (Element) childNode;
                        properties.put(property.getTagName(), property.getTextContent().trim());
                    }
                }
            }
        }
        return properties;
    }

    private String resolvePropertyValue(String value, Map<String, String> mavenProperties) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            String propertyName = value.substring(2, value.length() - 1);
            // Recursively resolve properties in case a property value is another property
            String resolved = mavenProperties.get(propertyName);
            if (resolved != null && resolved.startsWith("${") && resolved.endsWith("}") && !resolved.equals(value)) {
                 // Avoid infinite recursion if property refers to itself or a non-resolving cycle
                return resolvePropertyValue(resolved, mavenProperties);
            }
            return mavenProperties.getOrDefault(propertyName, value); // Return original value if property not found
        }
        return value;
    }

    private void addMavenDependencies(ParserConfig.Builder configBuilder, Document pomDoc, Map<String, String> mavenProperties) {
        // Get the local repository path
        Path localRepo = getMavenLocalRepository();
        System.out.println("DEBUG: Maven local repo path: " + localRepo);

        // Parse dependencies from pom.xml
        NodeList dependencies = pomDoc.getElementsByTagName("dependency");
        System.out.println("DEBUG: Found " + dependencies.getLength() + " <dependency> tags.");

        for (int i = 0; i < dependencies.getLength(); i++) {
            Node depNode = dependencies.item(i);
            if (!(depNode instanceof Element)) {
                continue;
            }
            Element dep = (Element) depNode;

            String rawGroupId = getElementContent(dep, "groupId");
            String groupId = resolvePropertyValue(rawGroupId, mavenProperties);

            String rawArtifactId = getElementContent(dep, "artifactId");
            String artifactId = resolvePropertyValue(rawArtifactId, mavenProperties);

            String rawVersion = getElementContent(dep, "version");
            String version = resolvePropertyValue(rawVersion, mavenProperties);

            String scope = getElementContent(dep, "scope");
            if (scope == null) {
                scope = "compile"; // Default scope
            }

            System.out.println("DEBUG: Processing dependency: G:" + groupId + ", A:" + artifactId + ", V:" + version + ", S:" + scope);

            // Include dependencies with scope 'compile', 'provided', or 'test'
            // For AST parsing, especially of test files, 'test' scope dependencies like JUnit are essential.
            if ("compile".equalsIgnoreCase(scope) || "provided".equalsIgnoreCase(scope) || "test".equalsIgnoreCase(scope)) {
                if (groupId != null && artifactId != null && version != null) {
                    // Convert groupId to path format
                    String groupPath = groupId.replace('.', '/');
                    
                    // Construct the path to the JAR file
                    Path jarPath = localRepo.resolve(groupPath)
                            .resolve(artifactId)
                            .resolve(version)
                            .resolve(artifactId + "-" + version + ".jar");
                    
                    System.out.println("DEBUG: Attempting to add to classpath: " + jarPath);
                    if (jarPath.toFile().exists()) {
                        configBuilder.classpath(jarPath.toString());
                        System.out.println("DEBUG: SUCCESS - Added to classpath: " + jarPath);
                    } else {
                        System.err.println("DEBUG: WARNING - JAR file not found: " + jarPath);
                    }
                } else {
                    System.err.println("DEBUG: WARNING - Skipping dependency due to missing G, A, or V. Original G:" + rawGroupId + ", A:"+ rawArtifactId + ", V:"+ rawVersion);
                }
            } else {
                System.out.println("DEBUG: Skipping dependency due to scope: " + scope + " (G:" + groupId + ", A:" + artifactId + ")");
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
                        Node repoNode = localRepoNodes.item(0);
                        if (repoNode != null) {
                             return Paths.get(repoNode.getTextContent().trim());
                        }
                    }
                } catch (Exception e) {
                    // Fall back to default
                    System.err.println("Warning: Failed to parse Maven settings.xml or read localRepository: " + e.getMessage() + ". Falling back to default.");
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
            Node item = elements.item(0);
            if (item != null) {
                return item.getTextContent().trim();
            }
        }
        return null;
    }
}
