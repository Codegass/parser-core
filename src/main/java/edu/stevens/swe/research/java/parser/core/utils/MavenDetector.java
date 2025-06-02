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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MavenDetector extends AbstractBuildToolDetector {
    private static final String POM_FILE = "pom.xml";
    private static final String SETTINGS_FILE = "settings.xml";
    private static final String DEFAULT_M2_PATH = ".m2" + File.separator + "repository";

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

            // Add current project's compiled classes and sources
            addCurrentProjectPaths(configBuilder, projectRoot);
            
            // Add multi-module project paths
            addMultiModulePaths(configBuilder, projectRoot, pomDoc);

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

        // Enhanced version collection strategy
        Map<String, String> explicitVersions = new HashMap<>();
        Map<String, String> managedVersions = new HashMap<>();
        Map<String, String> inheritedVersions = new HashMap<>();
        
        // Collect versions from current POM
        collectVersionsFromCurrentPom(pomDoc, explicitVersions);
        
        // Parse dependencyManagement section from current POM
        collectManagedVersions(pomDoc, managedVersions);
        
        // Parse parent POM chain for inherited versions
        collectInheritedVersions(pomDoc, localRepo, inheritedVersions, managedVersions);
        
        // Scan multi-module project for additional version patterns
        collectMultiModuleVersions(pomDoc, localRepo, explicitVersions, managedVersions);

        // Process regular dependencies
        processRegularDependencies(configBuilder, pomDoc, localRepo, explicitVersions);

        // Add common test dependencies with comprehensive version selection
        System.out.println("DEBUG: Adding common test dependencies with comprehensive version selection...");
        addCommonTestDependenciesAdvanced(configBuilder, localRepo, explicitVersions, managedVersions, inheritedVersions);
    }
    
    /**
     * Collects explicit dependency versions from the current POM
     */
    private void collectVersionsFromCurrentPom(Document pomDoc, Map<String, String> explicitVersions) {
        NodeList dependencies = pomDoc.getElementsByTagName("dependency");
        System.out.println("DEBUG: Found " + dependencies.getLength() + " <dependency> tags in current pom.xml");
        
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dep = (Element) dependencies.item(i);
            String groupId = getElementContent(dep, "groupId");
            String artifactId = getElementContent(dep, "artifactId");
            String version = getElementContent(dep, "version");
            
            if (groupId != null && artifactId != null && version != null) {
                String key = groupId + ":" + artifactId;
                String resolvedVersion = resolveVersionProperties(version, pomDoc);
                explicitVersions.put(key, resolvedVersion);
                System.out.println("DEBUG: Collected explicit version: " + key + " = " + resolvedVersion);
            }
        }
    }
    
    /**
     * Collects managed dependency versions from dependencyManagement section
     */
    private void collectManagedVersions(Document pomDoc, Map<String, String> managedVersions) {
        NodeList dependencyManagementList = pomDoc.getElementsByTagName("dependencyManagement");
        if (dependencyManagementList.getLength() > 0) {
            Element dependencyManagement = (Element) dependencyManagementList.item(0);
            NodeList managedDependencies = dependencyManagement.getElementsByTagName("dependency");
            
            System.out.println("DEBUG: Found " + managedDependencies.getLength() + " managed dependencies in current POM");
            
            for (int i = 0; i < managedDependencies.getLength(); i++) {
                Element dep = (Element) managedDependencies.item(i);
                String groupId = getElementContent(dep, "groupId");
                String artifactId = getElementContent(dep, "artifactId");
                String version = getElementContent(dep, "version");
                
                if (groupId != null && artifactId != null && version != null) {
                    String key = groupId + ":" + artifactId;
                    String resolvedVersion = resolveVersionProperties(version, pomDoc);
                    managedVersions.put(key, resolvedVersion);
                    System.out.println("DEBUG: Collected managed version: " + key + " = " + resolvedVersion);
                }
            }
        }
    }
    
    /**
     * Recursively collects versions from parent POM chain
     */
    private void collectInheritedVersions(Document pomDoc, Path localRepo, 
                                        Map<String, String> inheritedVersions, 
                                        Map<String, String> inheritedManagedVersions) {
        NodeList parentList = pomDoc.getElementsByTagName("parent");
        if (parentList.getLength() > 0) {
            Element parent = (Element) parentList.item(0);
            String parentGroupId = getElementContent(parent, "groupId");
            String parentArtifactId = getElementContent(parent, "artifactId");
            String parentVersion = getElementContent(parent, "version");
            
            if (parentGroupId != null && parentArtifactId != null && parentVersion != null) {
                System.out.println("DEBUG: Found parent POM: " + parentGroupId + ":" + parentArtifactId + ":" + parentVersion);
                
                // Try to load parent POM
                Path parentPomPath = findParentPom(parentGroupId, parentArtifactId, parentVersion, localRepo);
                if (parentPomPath != null) {
                    try {
                        Document parentDoc = parseXmlFile(parentPomPath.toFile());
                        
                        // Collect versions from parent
                        collectVersionsFromCurrentPom(parentDoc, inheritedVersions);
                        collectManagedVersions(parentDoc, inheritedManagedVersions);
                        
                        // Recursively process parent's parent
                        collectInheritedVersions(parentDoc, localRepo, inheritedVersions, inheritedManagedVersions);
                        
                    } catch (Exception e) {
                        System.out.println("DEBUG: Error parsing parent POM: " + e.getMessage());
                    }
                } else {
                    System.out.println("DEBUG: Parent POM not found in local repository");
                }
            }
        }
    }
    
    /**
     * Scans multi-module project for additional version patterns
     */
    private void collectMultiModuleVersions(Document pomDoc, Path localRepo,
                                          Map<String, String> explicitVersions,
                                          Map<String, String> managedVersions) {
        NodeList modulesList = pomDoc.getElementsByTagName("modules");
        if (modulesList.getLength() > 0) {
            Element modules = (Element) modulesList.item(0);
            NodeList moduleNodes = modules.getElementsByTagName("module");
            
            System.out.println("DEBUG: Found " + moduleNodes.getLength() + " modules in multi-module project");
            
            for (int i = 0; i < moduleNodes.getLength(); i++) {
                String moduleName = moduleNodes.item(i).getTextContent().trim();
                Path modulePomPath = Paths.get(".").resolve(moduleName).resolve("pom.xml");
                
                System.out.println("DEBUG: Scanning module: " + moduleName + " at " + modulePomPath);
                
                if (modulePomPath.toFile().exists()) {
                    try {
                        Document moduleDoc = parseXmlFile(modulePomPath.toFile());
                        collectVersionsFromCurrentPom(moduleDoc, explicitVersions);
                        collectManagedVersions(moduleDoc, managedVersions);
                    } catch (Exception e) {
                        System.out.println("DEBUG: Error parsing module POM " + moduleName + ": " + e.getMessage());
                    }
                }
            }
        }
    }
    
    /**
     * Enhanced version resolution with property support
     */
    private String resolveVersionProperties(String version, Document pomDoc) {
        if (version == null || !version.startsWith("${") || !version.endsWith("}")) {
            return version;
        }
        
        String propertyName = version.substring(2, version.length() - 1);
        
        // Try to resolve from properties section
        NodeList propertiesList = pomDoc.getElementsByTagName("properties");
        if (propertiesList.getLength() > 0) {
            Element propertiesElement = (Element) propertiesList.item(0);
            String resolvedVersion = getElementContent(propertiesElement, propertyName);
            if (resolvedVersion != null) {
                System.out.println("DEBUG: Resolved property '" + propertyName + "' to '" + resolvedVersion + "'");
                return resolvedVersion;
            }
        }
        
        // Handle project.version
        if ("project.version".equals(propertyName)) {
            Element projectElement = pomDoc.getDocumentElement();
            String projectVersion = getElementContent(projectElement, "version");
            if (projectVersion != null) {
                System.out.println("DEBUG: Resolved project.version to '" + projectVersion + "'");
                return projectVersion;
            }
        }
        
        System.out.println("DEBUG: Could not resolve property '" + propertyName + "', keeping original: " + version);
        return version;
    }
    
    /**
     * Finds parent POM in local Maven repository
     */
    private Path findParentPom(String groupId, String artifactId, String version, Path localRepo) {
        String groupPath = groupId.replace('.', File.separatorChar);
        Path parentPomPath = localRepo.resolve(groupPath)
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".pom");
        
        if (parentPomPath.toFile().exists()) {
            System.out.println("DEBUG: Found parent POM at: " + parentPomPath);
            return parentPomPath;
        }
        
        System.out.println("DEBUG: Parent POM not found at: " + parentPomPath);
        return null;
    }
    
    /**
     * Processes regular dependencies from POM
     */
    private void processRegularDependencies(ParserConfig.Builder configBuilder, Document pomDoc, 
                                          Path localRepo, Map<String, String> explicitVersions) {
        NodeList dependencies = pomDoc.getElementsByTagName("dependency");
        
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dep = (Element) dependencies.item(i);
            String groupId = getElementContent(dep, "groupId");
            String artifactId = getElementContent(dep, "artifactId");
            String version = getElementContent(dep, "version");
            String scope = getElementContent(dep, "scope");

            System.out.println("DEBUG: Processing dependency: groupId=" + groupId + ", artifactId=" + artifactId + ", version=" + version + ", scope=" + (scope != null ? scope : "default (compile)"));

            if (groupId != null && artifactId != null && version != null) {
                String resolvedVersion = resolveVersionProperties(version, pomDoc);
                String groupPath = groupId.replace('.', File.separatorChar);
                Path jarPath = localRepo.resolve(groupPath)
                        .resolve(artifactId)
                        .resolve(resolvedVersion)
                        .resolve(artifactId + "-" + resolvedVersion + ".jar");

                System.out.println("DEBUG: Attempting to locate JAR: " + jarPath.toString());
                
                if (jarPath.toFile().exists()) {
                    configBuilder.classpath(jarPath.toString());
                    System.out.println("DEBUG: Added to classpath: " + jarPath.toString());
                } else {
                    System.out.println("DEBUG: JAR NOT FOUND: " + jarPath.toString());
            }
            } else if (groupId != null && artifactId != null && version == null) {
                System.out.println("DEBUG: No version specified for " + groupId + ":" + artifactId + ", searching in local repository...");
                findAndAddAvailableVersions(configBuilder, localRepo, groupId, artifactId);
            } else {
                System.out.println("DEBUG: Skipping dependency due to missing groupId, artifactId, or version.");
            }
        }
    }

    /**
     * Advanced common test dependency resolution using comprehensive version information
     */
    private void addCommonTestDependenciesAdvanced(ParserConfig.Builder configBuilder, Path localRepo, 
                                                 Map<String, String> explicitVersions, 
                                                 Map<String, String> managedVersions,
                                                 Map<String, String> inheritedVersions) {
        String[][] commonTestDeps = {
            {"org.junit.jupiter", "junit-jupiter-api"},
            {"org.junit.jupiter", "junit-jupiter-engine"},
            {"org.junit.jupiter", "junit-jupiter-params"},
            {"org.mockito", "mockito-core"},
            {"org.hamcrest", "hamcrest"},
            {"org.assertj", "assertj-core"}
        };

        System.out.println("DEBUG: Starting comprehensive version selection for common test dependencies...");
        
        for (String[] dep : commonTestDeps) {
            String groupId = dep[0];
            String artifactId = dep[1];
            String key = groupId + ":" + artifactId;
            
            // Strategy 1: Explicit versions from current project and modules
            String explicitVersion = explicitVersions.get(key);
            if (explicitVersion != null) {
                System.out.println("DEBUG: Found explicit version for " + key + ": " + explicitVersion);
                addSpecificVersion(configBuilder, localRepo, groupId, artifactId, explicitVersion);
                continue;
            }
            
            // Strategy 2: Managed versions from current project and modules
            String managedVersion = managedVersions.get(key);
            if (managedVersion != null) {
                System.out.println("DEBUG: Found managed version for " + key + ": " + managedVersion);
                addSpecificVersion(configBuilder, localRepo, groupId, artifactId, managedVersion);
                continue;
            }
            
            // Strategy 3: Inherited versions from parent POM chain
            String inheritedVersion = inheritedVersions.get(key);
            if (inheritedVersion != null) {
                System.out.println("DEBUG: Found inherited version for " + key + ": " + inheritedVersion);
                addSpecificVersion(configBuilder, localRepo, groupId, artifactId, inheritedVersion);
                continue;
            }
            
            // Strategy 4: Intelligent version inference from related dependencies
            String inferredVersion = inferCompatibleVersion(groupId, artifactId, explicitVersions, managedVersions, inheritedVersions);
            if (inferredVersion != null) {
                System.out.println("DEBUG: Inferred compatible version for " + key + ": " + inferredVersion);
                addSpecificVersion(configBuilder, localRepo, groupId, artifactId, inferredVersion);
                continue;
            }
            
            // Strategy 5: Pattern-based version suggestion
            String patternVersion = suggestVersionFromPatterns(groupId, artifactId, explicitVersions, managedVersions, inheritedVersions);
            if (patternVersion != null) {
                System.out.println("DEBUG: Suggested version based on patterns for " + key + ": " + patternVersion);
                addSpecificVersion(configBuilder, localRepo, groupId, artifactId, patternVersion);
                continue;
            }
            
            // Strategy 6: Fallback to latest stable (avoid RC/SNAPSHOT versions)
            System.out.println("DEBUG: No explicit, managed, inherited, or inferred version found for " + key + ", falling back to latest stable");
            addLatestStableVersion(configBuilder, localRepo, groupId, artifactId);
        }
    }
    
    /**
     * Intelligent version inference based on ecosystem patterns
     */
    private String inferCompatibleVersion(String groupId, String artifactId, 
                                        Map<String, String> explicitVersions, 
                                        Map<String, String> managedVersions,
                                        Map<String, String> inheritedVersions) {
        // Combine all version sources for analysis
        Map<String, String> allVersions = new HashMap<>();
        allVersions.putAll(inheritedVersions);
        allVersions.putAll(managedVersions);
        allVersions.putAll(explicitVersions); // Explicit versions have highest priority
        
        // For JUnit Jupiter dependencies, ensure version consistency
        if ("org.junit.jupiter".equals(groupId)) {
            for (String key : allVersions.keySet()) {
                if (key.startsWith("org.junit.jupiter:")) {
                    String version = allVersions.get(key);
                    System.out.println("DEBUG: Found JUnit Jupiter version pattern: " + version);
                    return version;
                }
            }
        }
        
        // For Mockito, map to compatible versions based on JUnit version
        if ("org.mockito".equals(groupId) && "mockito-core".equals(artifactId)) {
            String junitVersion = findJUnitVersion(allVersions);
            if (junitVersion != null) {
                return mapMockitoVersion(junitVersion);
            }
        }
        
        // For AssertJ, use version patterns based on Java/JUnit versions
        if ("org.assertj".equals(groupId) && "assertj-core".equals(artifactId)) {
            String junitVersion = findJUnitVersion(allVersions);
            if (junitVersion != null) {
                return mapAssertJVersion(junitVersion);
            }
        }
        
        return null;
    }
    
    /**
     * Suggests version based on common patterns in the project
     */
    private String suggestVersionFromPatterns(String groupId, String artifactId, 
                                            Map<String, String> explicitVersions, 
                                            Map<String, String> managedVersions,
                                            Map<String, String> inheritedVersions) {
        Map<String, String> allVersions = new HashMap<>();
        allVersions.putAll(inheritedVersions);
        allVersions.putAll(managedVersions);
        allVersions.putAll(explicitVersions);
        
        // Analyze version patterns in the project
        Map<String, Integer> majorVersionCounts = new HashMap<>();
        
        for (String version : allVersions.values()) {
            if (version != null && version.matches("\\d+\\..*")) {
                String majorVersion = version.split("\\.")[0];
                majorVersionCounts.put(majorVersion, majorVersionCounts.getOrDefault(majorVersion, 0) + 1);
            }
        }
        
        // If project predominantly uses certain major versions, suggest compatible versions
        String dominantMajorVersion = majorVersionCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        
        if (dominantMajorVersion != null) {
            System.out.println("DEBUG: Dominant major version pattern in project: " + dominantMajorVersion);
            
            // Map to compatible test library versions
            if ("org.junit.jupiter".equals(groupId)) {
                return "5.10.1"; // Stable JUnit 5 version
            } else if ("org.mockito".equals(groupId)) {
                return "5.7.0"; // Compatible Mockito version
            } else if ("org.hamcrest".equals(groupId)) {
                return "2.2"; // Stable Hamcrest version
            } else if ("org.assertj".equals(groupId)) {
                return "3.24.2"; // Stable AssertJ version
            }
        }
        
        return null;
    }
    
    /**
     * Adds latest stable version (avoiding RC, SNAPSHOT, alpha, beta versions)
     */
    private void addLatestStableVersion(ParserConfig.Builder configBuilder, Path localRepo, String groupId, String artifactId) {
        try {
            String groupPath = groupId.replace('.', File.separatorChar);
            Path artifactDir = localRepo.resolve(groupPath).resolve(artifactId);
            
            if (!artifactDir.toFile().exists()) {
                System.out.println("DEBUG: Artifact directory not found for " + groupId + ":" + artifactId);
                return;
            }
            
            File[] versionDirs = artifactDir.toFile().listFiles(File::isDirectory);
            if (versionDirs == null || versionDirs.length == 0) {
                System.out.println("DEBUG: No version directories found for " + groupId + ":" + artifactId);
                return;
            }
            
            // Filter stable versions and sort
            List<String> stableVersions = Arrays.stream(versionDirs)
                    .map(File::getName)
                    .filter(this::isStableVersion)
                    .sorted((a, b) -> compareVersions(b, a)) // Descending order
                    .collect(java.util.stream.Collectors.toList());
            
            System.out.println("DEBUG: Found " + stableVersions.size() + " stable versions for " + groupId + ":" + artifactId);
            
            for (String version : stableVersions) {
                Path jarPath = artifactDir.resolve(version).resolve(artifactId + "-" + version + ".jar");
                if (jarPath.toFile().exists()) {
                    configBuilder.classpath(jarPath.toString());
                    System.out.println("DEBUG: Added latest stable version " + groupId + ":" + artifactId + ":" + version + " to classpath");
                    return;
                }
            }
            
            System.out.println("DEBUG: No stable JAR files found for " + groupId + ":" + artifactId);
            
        } catch (Exception e) {
            System.out.println("DEBUG: Error finding stable version for " + groupId + ":" + artifactId + ": " + e.getMessage());
        }
    }
    
    /**
     * Checks if a version string represents a stable release
     */
    private boolean isStableVersion(String version) {
        String lowerVersion = version.toLowerCase();
        return !lowerVersion.contains("snapshot") &&
               !lowerVersion.contains("rc") &&
               !lowerVersion.contains("alpha") &&
               !lowerVersion.contains("beta") &&
               !lowerVersion.contains("m1") &&
               !lowerVersion.contains("m2") &&
               !lowerVersion.contains("cr") &&
               version.matches("\\d+\\.\\d+.*"); // Must start with major.minor
    }
    
    private String findJUnitVersion(Map<String, String> allVersions) {
        for (String key : allVersions.keySet()) {
            if (key.startsWith("org.junit.jupiter:")) {
                return allVersions.get(key);
            }
        }
        return null;
    }
    
    private String mapMockitoVersion(String junitVersion) {
        if (junitVersion.startsWith("5.")) {
            return "5.7.0"; // Compatible Mockito version for JUnit 5
        }
        return "4.11.0"; // Fallback
    }
    
    private String mapAssertJVersion(String junitVersion) {
        if (junitVersion.startsWith("5.")) {
            return "3.24.2"; // Compatible AssertJ version for JUnit 5
        }
        return "3.21.0"; // Fallback
    }

    private Path getMavenLocalRepository() {
        // First check M2_HOME environment variable
        String m2Home = System.getenv("M2_HOME");
        if (m2Home != null) {
            Path settingsPath = Paths.get(m2Home).resolve("conf").resolve(SETTINGS_FILE);
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
        Path jrtFs = javaHome.resolve("lib").resolve("jrt-fs.jar");
        
        // For Java 8 and earlier
        Path rtJar = javaHome.resolve("lib").resolve("rt.jar");
        
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

    /**
     * Adds a specific version of a dependency to the classpath
     */
    private void addSpecificVersion(ParserConfig.Builder configBuilder, Path localRepo, 
                                  String groupId, String artifactId, String version) {
        String groupPath = groupId.replace('.', File.separatorChar);
        Path jarPath = localRepo.resolve(groupPath)
                .resolve(artifactId)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".jar");

        System.out.println("DEBUG: Checking for specific version: " + jarPath);
        if (jarPath.toFile().exists()) {
            configBuilder.classpath(jarPath.toString());
            System.out.println("DEBUG: Added " + groupId + ":" + artifactId + ":" + version + " to classpath");
        } else {
            System.out.println("DEBUG: Specific version NOT FOUND: " + jarPath + ", falling back to latest stable");
            // If the specific version is not found, fall back to latest stable
            addLatestStableVersion(configBuilder, localRepo, groupId, artifactId);
        }
    }
    
    /**
     * Searches for all available versions of a dependency in the local Maven repository
     * and adds them to the classpath.
     */
    private void findAndAddAvailableVersions(ParserConfig.Builder configBuilder, Path localRepo, String groupId, String artifactId) {
        try {
            String groupPath = groupId.replace('.', File.separatorChar);
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
            
            // Sort version directories to prefer newer versions and filter stable ones
            List<String> stableVersions = Arrays.stream(versionDirs)
                    .map(File::getName)
                    .filter(this::isStableVersion)
                    .sorted((a, b) -> compareVersions(b, a))
                    .collect(java.util.stream.Collectors.toList());
            
            boolean foundAny = false;
            for (String version : stableVersions) {
                Path jarPath = artifactDir.resolve(version).resolve(artifactId + "-" + version + ".jar");
                
                if (jarPath.toFile().exists()) {
                    configBuilder.classpath(jarPath.toString());
                    System.out.println("DEBUG: Added available stable version to classpath: " + jarPath);
                    foundAny = true;
                    break; // Take the first (latest) stable version
                }
            }
            
            if (!foundAny) {
                System.out.println("DEBUG: No stable JAR files found for " + groupId + ":" + artifactId);
            }
            
        } catch (Exception e) {
            System.out.println("DEBUG: Error searching for versions of " + groupId + ":" + artifactId + ": " + e.getMessage());
        }
    }
    
    /**
     * Simple version comparison. Tries to handle semantic versioning patterns.
     * Returns positive if version1 > version2, negative if version1 < version2, 0 if equal.
     */
    private int compareVersions(String version1, String version2) {
        if (version1.equals(version2)) {
            return 0;
        }
        
        try {
            // Split versions by dots and compare numerically
            String[] parts1 = version1.split("\\.");
            String[] parts2 = version2.split("\\.");
            
            int maxLength = Math.max(parts1.length, parts2.length);
            
            for (int i = 0; i < maxLength; i++) {
                String part1 = i < parts1.length ? parts1[i] : "0";
                String part2 = i < parts2.length ? parts2[i] : "0";
                
                // Extract numeric part (ignore non-numeric suffixes like "-SNAPSHOT", "-beta", etc.)
                Integer num1 = extractNumericPart(part1);
                Integer num2 = extractNumericPart(part2);
                
                if (num1 != null && num2 != null) {
                    int comparison = num1.compareTo(num2);
                    if (comparison != 0) {
                        return comparison;
                    }
                } else {
                    // Fall back to string comparison if numeric extraction fails
                    int comparison = part1.compareTo(part2);
                    if (comparison != 0) {
                        return comparison;
                    }
                }
            }
            
            return 0; // Versions are equal
            
        } catch (Exception e) {
            // Fall back to simple string comparison if parsing fails
            return version1.compareTo(version2);
        }
    }
    
    /**
     * Extracts the numeric part from a version component (e.g., "5" from "5-SNAPSHOT")
     */
    private Integer extractNumericPart(String versionPart) {
        try {
            // Find the first non-digit character
            StringBuilder numericPart = new StringBuilder();
            for (char c : versionPart.toCharArray()) {
                if (Character.isDigit(c)) {
                    numericPart.append(c);
                } else {
                    break;
                }
            }
            
            if (numericPart.length() > 0) {
                return Integer.parseInt(numericPart.toString());
            }
        } catch (NumberFormatException e) {
            // Ignore and return null
        }
        return null;
    }

    /**
     * Adds current project's compiled classes and source directories
     */
    private void addCurrentProjectPaths(ParserConfig.Builder configBuilder, Path projectRoot) {
        System.out.println("DEBUG: Adding current project paths for: " + projectRoot);
        
        // Add compiled classes directory (main)
        Path targetClasses = projectRoot.resolve("target").resolve("classes");
        if (targetClasses.toFile().exists()) {
            configBuilder.classpath(targetClasses.toString());
            System.out.println("DEBUG: Added target/classes to classpath: " + targetClasses);
        }

        // Add compiled test classes directory
        Path targetTestClasses = projectRoot.resolve("target").resolve("test-classes");
        if (targetTestClasses.toFile().exists()) {
            configBuilder.classpath(targetTestClasses.toString());
            System.out.println("DEBUG: Added target/test-classes to classpath: " + targetTestClasses);
        }

        // Add main resources directory
        Path mainResources = projectRoot.resolve("src").resolve("main").resolve("resources");
        if (mainResources.toFile().exists()) {
            configBuilder.classpath(mainResources.toString());
            System.out.println("DEBUG: Added main resources to classpath: " + mainResources);
        }

        // Add test resources directory
        Path testResources = projectRoot.resolve("src").resolve("test").resolve("resources");
        if (testResources.toFile().exists()) {
            configBuilder.classpath(testResources.toString());
            System.out.println("DEBUG: Added test resources to classpath: " + testResources);
        }

        // Add main source directory to sourcepath
        Path mainSources = projectRoot.resolve("src").resolve("main").resolve("java");
        if (mainSources.toFile().exists()) {
            configBuilder.sourcepath(mainSources.toString());
            System.out.println("DEBUG: Added main sources to sourcepath: " + mainSources);
        }

        // Add test source directory to sourcepath
        Path testSources = projectRoot.resolve("src").resolve("test").resolve("java");
        if (testSources.toFile().exists()) {
            configBuilder.sourcepath(testSources.toString());
            System.out.println("DEBUG: Added test sources to sourcepath: " + testSources);
        }
    }
    
    /**
     * Adds paths from all modules in a multi-module project
     */
    private void addMultiModulePaths(ParserConfig.Builder configBuilder, Path projectRoot, Document pomDoc) {
        NodeList modulesList = pomDoc.getElementsByTagName("modules");
        if (modulesList.getLength() > 0) {
            Element modules = (Element) modulesList.item(0);
            NodeList moduleNodes = modules.getElementsByTagName("module");
            
            System.out.println("DEBUG: Processing " + moduleNodes.getLength() + " modules for sourcepath and classpath");
            
            for (int i = 0; i < moduleNodes.getLength(); i++) {
                String moduleName = moduleNodes.item(i).getTextContent().trim();
                Path moduleRoot = projectRoot.resolve(moduleName);
                
                System.out.println("DEBUG: Processing module: " + moduleName + " at " + moduleRoot);
                
                if (moduleRoot.toFile().exists() && moduleRoot.toFile().isDirectory()) {
                    addModulePaths(configBuilder, moduleRoot, moduleName);
                }
            }
        } else {
            System.out.println("DEBUG: No modules found, this is a single-module project");
        }
    }
    
    /**
     * Adds paths for a specific module
     */
    private void addModulePaths(ParserConfig.Builder configBuilder, Path moduleRoot, String moduleName) {
        // Add module's compiled classes to classpath
        Path moduleTargetClasses = moduleRoot.resolve("target").resolve("classes");
        if (moduleTargetClasses.toFile().exists()) {
            configBuilder.classpath(moduleTargetClasses.toString());
            System.out.println("DEBUG: Added module " + moduleName + " target/classes: " + moduleTargetClasses);
        }
        
        Path moduleTargetTestClasses = moduleRoot.resolve("target").resolve("test-classes");
        if (moduleTargetTestClasses.toFile().exists()) {
            configBuilder.classpath(moduleTargetTestClasses.toString());
            System.out.println("DEBUG: Added module " + moduleName + " target/test-classes: " + moduleTargetTestClasses);
        }
        
        // Add module's resources to classpath
        Path moduleMainResources = moduleRoot.resolve("src").resolve("main").resolve("resources");
        if (moduleMainResources.toFile().exists()) {
            configBuilder.classpath(moduleMainResources.toString());
            System.out.println("DEBUG: Added module " + moduleName + " main resources: " + moduleMainResources);
        }
        
        Path moduleTestResources = moduleRoot.resolve("src").resolve("test").resolve("resources");
        if (moduleTestResources.toFile().exists()) {
            configBuilder.classpath(moduleTestResources.toString());
            System.out.println("DEBUG: Added module " + moduleName + " test resources: " + moduleTestResources);
        }
        
        // Add module's source directories to sourcepath
        Path moduleMainSources = moduleRoot.resolve("src").resolve("main").resolve("java");
        if (moduleMainSources.toFile().exists()) {
            configBuilder.sourcepath(moduleMainSources.toString());
            System.out.println("DEBUG: Added module " + moduleName + " main sources: " + moduleMainSources);
        }
        
        Path moduleTestSources = moduleRoot.resolve("src").resolve("test").resolve("java");
        if (moduleTestSources.toFile().exists()) {
            configBuilder.sourcepath(moduleTestSources.toString());
            System.out.println("DEBUG: Added module " + moduleName + " test sources: " + moduleTestSources);
        }
    }
}
