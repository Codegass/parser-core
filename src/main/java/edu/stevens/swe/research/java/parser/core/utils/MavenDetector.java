package edu.stevens.swe.research.java.parser.core.utils;

import edu.stevens.swe.research.java.parser.core.core.ParserConfig;
import edu.stevens.swe.research.java.parser.core.utils.exceptions.ProjectDetectionException;
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
        System.out.println("DEBUG: Using local Maven repository: " + localRepo); // Log local repo path

        // Parse dependencies from pom.xml
        NodeList dependencies = pomDoc.getElementsByTagName("dependency");
        System.out.println("DEBUG: Found " + dependencies.getLength() + " <dependency> tags in pom.xml");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dep = (Element) dependencies.item(i);
            String groupId = getElementContent(dep, "groupId");
            String artifactId = getElementContent(dep, "artifactId");
            String version = getElementContent(dep, "version");
            String scope = getElementContent(dep, "scope"); // Get scope

            // Basic property resolution for version (e.g., ${project.version})
            // This is a simplified version and might not cover all cases (e.g., properties defined in parent POMs or settings.xml)
            if (version != null && version.startsWith("${") && version.endsWith("}")) {
                String propertyName = version.substring(2, version.length() - 1);
                // Attempt to resolve from project properties (e.g. <properties><some.version>1.0</some.version></properties>)
                NodeList propertiesList = pomDoc.getElementsByTagName("properties");
                if (propertiesList.getLength() > 0) {
                    Element propertiesElement = (Element) propertiesList.item(0); // Assuming properties are defined in the first <properties> block
                    String resolvedVersion = getElementContent(propertiesElement, propertyName);
                    if (resolvedVersion != null) {
                        System.out.println("DEBUG: Resolved version property '" + propertyName + "' to '" + resolvedVersion + "' for " + groupId + ":" + artifactId);
                        version = resolvedVersion;
                    } else if ("project.version".equals(propertyName)) {
                        // Try to get version from parent <version> tag if not found in properties
                        // This assumes the <version> tag is a direct child of the <project> (root) element
                        Element projectElement = pomDoc.getDocumentElement();
                        String projectVersion = getElementContent(projectElement,"version");
                        if(projectVersion != null){
                             System.out.println("DEBUG: Resolved version property 'project.version' to '" + projectVersion + "' for " + groupId + ":" + artifactId);
                            version = projectVersion;
                        } else {
                            System.out.println("DEBUG: Could not resolve version property '" + propertyName + "' for " + groupId + ":" + artifactId);
                        }
                    }
                     else {
                        System.out.println("DEBUG: Could not resolve version property '" + propertyName + "' for " + groupId + ":" + artifactId);
                    }
                } else if ("project.version".equals(propertyName)) {
                     Element projectElement = pomDoc.getDocumentElement();
                        String projectVersion = getElementContent(projectElement,"version");
                        if(projectVersion != null){
                             System.out.println("DEBUG: Resolved version property 'project.version' to '" + projectVersion + "' for " + groupId + ":" + artifactId);
                            version = projectVersion;
                        } else {
                            System.out.println("DEBUG: Could not resolve version property '" + propertyName + "' for " + groupId + ":" + artifactId);
                        }
                }
                else {
                    System.out.println("DEBUG: <properties> tag not found, cannot resolve version property '" + propertyName + "' for " + groupId + ":" + artifactId);
                }
            }


            System.out.println("DEBUG: Processing dependency: groupId=" + groupId + ", artifactId=" + artifactId + ", version=" + version + ", scope=" + (scope != null ? scope : "default (compile)"));


            if (groupId != null && artifactId != null && version != null) {
                // Convert groupId to path format
                String groupPath = groupId.replace('.', '/');

                // Construct the path to the JAR file
                Path jarPath = localRepo.resolve(groupPath)
                        .resolve(artifactId)
                        .resolve(version)
                        .resolve(artifactId + "-" + version + ".jar");

                System.out.println("DEBUG: Attempting to locate JAR: " + jarPath.toString()); // Log constructed JAR path

                if (jarPath.toFile().exists()) {
                    configBuilder.classpath(jarPath.toString());
                    System.out.println("DEBUG: Added to classpath: " + jarPath.toString());
                } else {
                    System.out.println("DEBUG: JAR NOT FOUND: " + jarPath.toString());
                }
            } else if (groupId != null && artifactId != null && version == null) {
                // Handle dependencies without explicit version - search in local repo for any available version
                System.out.println("DEBUG: No version specified for " + groupId + ":" + artifactId + ", searching in local repository...");
                findAndAddAvailableVersions(configBuilder, localRepo, groupId, artifactId);
            } else {
                System.out.println("DEBUG: Skipping dependency due to missing groupId, artifactId, or version.");
            }
        }

        // WORKAROUND: Add common test dependencies that might be missing due to version management issues
        System.out.println("DEBUG: Adding common test dependencies with default versions...");
        addCommonTestDependencies(configBuilder, localRepo);
    }

    /**
     * Searches for all available versions of a dependency in the local Maven repository
     * and adds them to the classpath.
     */
    private void findAndAddAvailableVersions(ParserConfig.Builder configBuilder, Path localRepo, String groupId, String artifactId) {
        try {
            String groupPath = groupId.replace('.', '/');
            Path artifactDir = localRepo.resolve(groupPath).resolve(artifactId);
            
            System.out.println("DEBUG: Searching for versions in: " + artifactDir);
            
            if (!artifactDir.toFile().exists() || !artifactDir.toFile().isDirectory()) {
                System.out.println("DEBUG: Artifact directory not found: " + artifactDir);
                return;
            }
            
            File[] versionDirs = artifactDir.toFile().listFiles(File::isDirectory);
            if (versionDirs == null || versionDirs.length == 0) {
                System.out.println("DEBUG: No version directories found for " + groupId + ":" + artifactId);
                return;
            }
            
            // Sort version directories to prefer newer versions (simple string sort, could be improved)
            java.util.Arrays.sort(versionDirs, (a, b) -> b.getName().compareTo(a.getName()));
            
            boolean foundAny = false;
            for (File versionDir : versionDirs) {
                String version = versionDir.getName();
                Path jarPath = versionDir.toPath().resolve(artifactId + "-" + version + ".jar");
                
                if (jarPath.toFile().exists()) {
                    configBuilder.classpath(jarPath.toString());
                    System.out.println("DEBUG: Added available version to classpath: " + jarPath);
                    foundAny = true;
                    // For now, just take the first (latest) version we find
                    // Could be modified to add all versions if needed
                    break;
                }
            }
            
            if (!foundAny) {
                System.out.println("DEBUG: No JAR files found for any version of " + groupId + ":" + artifactId);
            }
            
        } catch (Exception e) {
            System.out.println("DEBUG: Error searching for versions of " + groupId + ":" + artifactId + ": " + e.getMessage());
        }
    }

    /**
     * Adds common test dependencies with default versions when they are not resolved from pom.xml
     * This is a workaround for dependencies that have versions managed by parent POMs.
     */
    private void addCommonTestDependencies(ParserConfig.Builder configBuilder, Path localRepo) {
        // Common JUnit 5 dependencies
        String[][] commonTestDeps = {
            {"org.junit.jupiter", "junit-jupiter-api", "5.11.4"},
            {"org.junit.jupiter", "junit-jupiter-engine", "5.11.4"},
            {"org.junit.jupiter", "junit-jupiter-params", "5.11.4"},
            {"org.mockito", "mockito-core", "5.15.2"},
            {"org.hamcrest", "hamcrest", "3.0"},
            {"org.assertj", "assertj-core", "3.26.3"}
        };

        for (String[] dep : commonTestDeps) {
            String groupId = dep[0];
            String artifactId = dep[1];
            String version = dep[2];

            String groupPath = groupId.replace('.', '/');
            Path jarPath = localRepo.resolve(groupPath)
                    .resolve(artifactId)
                    .resolve(version)
                    .resolve(artifactId + "-" + version + ".jar");

            System.out.println("DEBUG: Checking for common test dependency: " + jarPath);
            if (jarPath.toFile().exists()) {
                configBuilder.classpath(jarPath.toString());
                System.out.println("DEBUG: Added common test dependency to classpath: " + jarPath);
            } else {
                System.out.println("DEBUG: Common test dependency NOT FOUND: " + jarPath);
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
