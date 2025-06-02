package edu.stevens.swe.research.java.parser.core.utils;

import edu.stevens.swe.research.java.parser.core.core.ParserConfig;
import edu.stevens.swe.research.java.parser.core.utils.exceptions.ProjectDetectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.idea.IdeaProject;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency;
import org.gradle.tooling.model.idea.IdeaContentRoot;
import org.gradle.tooling.model.idea.IdeaSourceDirectory;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;
import org.gradle.tooling.model.eclipse.EclipseOutputLocation;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProjectDependency;
import org.gradle.tooling.model.eclipse.EclipseClasspathEntry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Stream;

public class GradleDetector extends AbstractBuildToolDetector {
    private static final String BUILD_GRADLE = "build.gradle";
    private static final String SETTINGS_GRADLE = "settings.gradle";
    private static final String GRADLE_CACHE_PATH = ".gradle" + File.separator + "caches" + File.separator + "modules-2" + File.separator + "files-2.1";

    @Override
    public boolean supports(Path projectRoot) {
        return fileExists(projectRoot, BUILD_GRADLE) || fileExists(projectRoot, SETTINGS_GRADLE);
    }

    @Override
    public ParserConfig detect(Path projectRoot) throws ProjectDetectionException {
        if (!supports(projectRoot)) {
            throw new ProjectDetectionException("Not a Gradle project: " + projectRoot);
        }

        try {
            ParserConfig.Builder configBuilder = createBaseConfig();

            // Enhanced multi-module Gradle project detection
            addEnhancedGradleProjectDetails(configBuilder, projectRoot);

            // Add JDK libraries
            addJdkLibraries(configBuilder);

            return configBuilder.build();
        } catch (Exception e) {
            throw new ProjectDetectionException("Failed to detect Gradle project configuration", e);
        }
    }

    private void addEnhancedGradleProjectDetails(ParserConfig.Builder configBuilder, Path projectRoot) {
        Set<String> processedPaths = new HashSet<>();
        
        // Parse project-specific dependency definitions first
        Map<String, String> projectDependencyVersions = parseProjectDependencyDefinitions(projectRoot);
        
        // Smart Gradle compatibility handling
        String gradleVersion = detectGradleVersion(projectRoot);
        boolean useToolingApi = isGradleToolingApiCompatible(gradleVersion);
        
        if (useToolingApi) {
            try {
                System.out.println("DEBUG: Using Gradle Tooling API for version: " + gradleVersion);
                addGradleToolingApiDetails(configBuilder, projectRoot, processedPaths);
            } catch (Exception e) {
                System.err.println("DEBUG: Gradle Tooling API failed: " + e.getMessage());
                System.err.println("DEBUG: Falling back to file system based detection...");
                useToolingApi = false;
            }
        } else {
            System.out.println("DEBUG: Gradle version " + gradleVersion + " not compatible with Tooling API, using file system detection");
        }
        
        // Always add file system based detection for better coverage (or as fallback)
        addFileSystemBasedGradleDetails(configBuilder, projectRoot, processedPaths);
        
        // Add enhanced test dependencies using project-specific versions
        addEnhancedTestDependencies(configBuilder, projectRoot, projectDependencyVersions, processedPaths);
    }

    /**
     * Parse project-specific dependency definitions from gradle files
     */
    private Map<String, String> parseProjectDependencyDefinitions(Path projectRoot) {
        Map<String, String> dependencies = new HashMap<>();
        
        // Look for dependency definition files in gradle/scripts directory
        Path gradleScriptsDir = projectRoot.resolve("gradle").resolve("scripts");
        Path dependencyDefsFile = gradleScriptsDir.resolve("dependencyDefinitions.gradle");
        
        if (dependencyDefsFile.toFile().exists()) {
            System.out.println("DEBUG: Found dependency definitions file: " + dependencyDefsFile);
            parseDependencyDefinitionsFile(dependencyDefsFile, dependencies);
        }
        
        return dependencies;
    }
    
    /**
     * Parse dependency definitions from gradle script file
     */
    private void parseDependencyDefinitionsFile(Path file, Map<String, String> dependencies) {
        try {
            List<String> lines = Files.readAllLines(file);
            boolean inExternalDependency = false;
            
            for (String line : lines) {
                line = line.trim();
                
                if (line.contains("ext.externalDependency = [")) {
                    inExternalDependency = true;
                    continue;
                }
                
                if (inExternalDependency) {
                    if (line.equals("]")) {
                        break; // End of externalDependency block
                    }
                    
                    // Parse dependency entries like: "testng": "org.testng:testng:6.14.3",
                    Pattern pattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");
                    Matcher matcher = pattern.matcher(line);
                    
                    if (matcher.find()) {
                        String key = matcher.group(1);
                        String value = matcher.group(2);
                        dependencies.put(key, value);
                        System.out.println("DEBUG: Parsed dependency: " + key + " = " + value);
                    }
                }
            }
            
            System.out.println("DEBUG: Parsed " + dependencies.size() + " dependency definitions from project");
            
        } catch (IOException e) {
            System.err.println("DEBUG: Error parsing dependency definitions file: " + e.getMessage());
        }
    }

    private void addGradleToolingApiDetails(ParserConfig.Builder configBuilder, Path projectRoot, Set<String> processedPaths) {
        ProjectConnection connection = null;
        try {
            System.out.println("DEBUG: Connecting to Gradle project using Tooling API...");
            connection = GradleConnector.newConnector()
                    .forProjectDirectory(projectRoot.toFile())
                    .connect();

            // Get IDEA project model for comprehensive module information
            IdeaProject ideaProject = connection.model(IdeaProject.class).get();
            System.out.println("DEBUG: Found " + ideaProject.getModules().size() + " Gradle modules via Tooling API");

            for (IdeaModule module : ideaProject.getModules()) {
                System.out.println("DEBUG: Processing Tooling API module: " + module.getName());
                
                // Add module source directories
                for (IdeaContentRoot contentRoot : module.getContentRoots()) {
                    for (IdeaSourceDirectory sourceDir : contentRoot.getSourceDirectories()) {
                        File dirFile = sourceDir.getDirectory();
                        if (dirFile.exists() && !processedPaths.contains(dirFile.getAbsolutePath())) {
                            configBuilder.sourcepath(dirFile.getAbsolutePath());
                            processedPaths.add(dirFile.getAbsolutePath());
                            System.out.println("DEBUG: Added Tooling API source directory: " + dirFile.getAbsolutePath());
                        }
                    }
                }

                // Add module dependencies (JARs)
                System.out.println("DEBUG: Processing " + module.getDependencies().size() + " dependencies for module: " + module.getName());
                module.getDependencies().forEach(dependency -> {
                    if (dependency instanceof IdeaSingleEntryLibraryDependency) {
                        File file = ((IdeaSingleEntryLibraryDependency) dependency).getFile();
                        if (file != null && file.exists() && file.getName().endsWith(".jar") && !processedPaths.contains(file.getAbsolutePath())) {
                            configBuilder.classpath(file.getAbsolutePath());
                            processedPaths.add(file.getAbsolutePath());
                            System.out.println("DEBUG: Added Tooling API dependency JAR: " + file.getAbsolutePath());
                        } else if (file != null && !file.exists()) {
                            System.out.println("DEBUG: Tooling API dependency JAR not found: " + file.getAbsolutePath());
                        }
                    }
                });
            }

            // Use EclipseProject model for additional classpath information
            EclipseProject eclipseProject = connection.model(EclipseProject.class).get();
            System.out.println("DEBUG: Processing Eclipse project model for additional classpath entries");
            
            for (EclipseSourceDirectory eclipseSourceDir : eclipseProject.getSourceDirectories()) {
                File dirFile = eclipseSourceDir.getDirectory();
                if (dirFile.exists() && !processedPaths.contains(dirFile.getAbsolutePath())) {
                    configBuilder.sourcepath(dirFile.getAbsolutePath());
                    processedPaths.add(dirFile.getAbsolutePath());
                    System.out.println("DEBUG: Added Eclipse source directory: " + dirFile.getAbsolutePath());
                }
            }

            // Add Eclipse output locations
            EclipseOutputLocation outputLocation = eclipseProject.getOutputLocation();
            if (outputLocation != null) {
                File outputDir = new File(outputLocation.getPath());
                if (outputDir.exists()) {
                    String outputPath = outputDir.getAbsolutePath();
                    if (!processedPaths.contains(outputPath)) {
                        configBuilder.classpath(outputPath);
                        processedPaths.add(outputPath);
                        System.out.println("DEBUG: Added Eclipse output directory: " + outputPath);
                    }
                }
            }

            // Add classpath entries from Eclipse model
            System.out.println("DEBUG: Processing " + eclipseProject.getClasspath().size() + " Eclipse classpath entries");
            for (EclipseClasspathEntry cpEntry : eclipseProject.getClasspath()) {
                if (cpEntry instanceof EclipseExternalDependency) {
                    EclipseExternalDependency externalDep = (EclipseExternalDependency) cpEntry;
                    File file = externalDep.getFile();
                    if (file != null && file.exists() && file.getName().endsWith(".jar") && !processedPaths.contains(file.getAbsolutePath())) {
                        configBuilder.classpath(file.getAbsolutePath());
                        processedPaths.add(file.getAbsolutePath());
                        System.out.println("DEBUG: Added Eclipse external dependency: " + file.getAbsolutePath());
                    } else if (file != null && !file.exists()) {
                        System.out.println("DEBUG: Eclipse external dependency not found: " + file.getAbsolutePath());
                    }
                } else if (cpEntry instanceof EclipseProjectDependency) {
                    EclipseProjectDependency projectDep = (EclipseProjectDependency) cpEntry;
                    System.out.println("DEBUG: Found Eclipse project dependency: " + projectDep.getPath());
                    // TODO: Add project dependency handling if needed
                }
            }

        } catch (Exception e) {
            System.err.println("DEBUG: Error using Gradle Tooling API: " + e.getMessage());
            throw e;
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    /**
     * Enhanced file system based detection for multi-module Gradle projects
     */
    private void addFileSystemBasedGradleDetails(ParserConfig.Builder configBuilder, Path projectRoot, Set<String> processedPaths) {
        System.out.println("DEBUG: Starting file system based Gradle project detection...");
        
        // Discover all modules by finding build.gradle files
        Set<Path> allModules = discoverGradleModules(projectRoot);
        System.out.println("DEBUG: Discovered " + allModules.size() + " Gradle modules via file system");
        
        for (Path moduleRoot : allModules) {
            String moduleName = projectRoot.relativize(moduleRoot).toString();
            if (moduleName.isEmpty()) {
                moduleName = "root";
            }
            System.out.println("DEBUG: Processing file system module: " + moduleName + " at " + moduleRoot);
            
            // Add source directories for this module
            addModuleSourceDirectories(configBuilder, moduleRoot, moduleName, processedPaths);
            
            // Add build output directories for this module
            addModuleBuildOutputs(configBuilder, moduleRoot, moduleName, processedPaths);
        }
    }

    /**
     * Discover all Gradle modules by finding build.gradle files
     */
    private Set<Path> discoverGradleModules(Path projectRoot) {
        Set<Path> modules = new HashSet<>();
        
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(path -> path.getFileName().toString().equals(BUILD_GRADLE))
                 .map(Path::getParent)
                 .forEach(moduleRoot -> {
                     modules.add(moduleRoot);
                     System.out.println("DEBUG: Found Gradle module at: " + moduleRoot);
                 });
        } catch (IOException e) {
            System.err.println("DEBUG: Error discovering Gradle modules: " + e.getMessage());
        }
        
        return modules;
    }

    /**
     * Add source directories for a specific module
     */
    private void addModuleSourceDirectories(ParserConfig.Builder configBuilder, Path moduleRoot, String moduleName, Set<String> processedPaths) {
        // Standard Gradle source directories
        String[][] sourceDirs = {
            {"src", "main", "java"},      // Main Java sources
            {"src", "test", "java"},      // Test Java sources
            {"src", "main", "resources"}, // Main resources
            {"src", "test", "resources"}  // Test resources
        };
        
        for (String[] dirParts : sourceDirs) {
            Path sourceDir = moduleRoot;
            for (String part : dirParts) {
                sourceDir = sourceDir.resolve(part);
            }
            
            if (sourceDir.toFile().exists() && !processedPaths.contains(sourceDir.toString())) {
                if (dirParts[dirParts.length - 1].equals("java")) {
                    configBuilder.sourcepath(sourceDir.toString());
                    System.out.println("DEBUG: Added module " + moduleName + " source directory: " + sourceDir);
                } else {
                    configBuilder.classpath(sourceDir.toString());
                    System.out.println("DEBUG: Added module " + moduleName + " resource directory: " + sourceDir);
                }
                processedPaths.add(sourceDir.toString());
            }
        }
    }

    /**
     * Add build output directories for a specific module
     */
    private void addModuleBuildOutputs(ParserConfig.Builder configBuilder, Path moduleRoot, String moduleName, Set<String> processedPaths) {
        // Standard Gradle build output directories
        String[][] buildDirs = {
            {"build", "classes", "java", "main"},     // Main compiled classes
            {"build", "classes", "java", "test"},     // Test compiled classes
            {"build", "resources", "main"},           // Main processed resources
            {"build", "resources", "test"},           // Test processed resources
            {"build", "classes", "main"},             // Alternative main classes (older Gradle)
            {"build", "classes", "test"}              // Alternative test classes (older Gradle)
        };
        
        for (String[] dirParts : buildDirs) {
            Path buildDir = moduleRoot;
            for (String part : dirParts) {
                buildDir = buildDir.resolve(part);
            }
            
            if (buildDir.toFile().exists() && !processedPaths.contains(buildDir.toString())) {
                configBuilder.classpath(buildDir.toString());
                processedPaths.add(buildDir.toString());
                System.out.println("DEBUG: Added module " + moduleName + " build directory: " + buildDir);
            }
        }
        
        // Also check for JAR outputs in build/libs
        Path libsDir = moduleRoot.resolve("build").resolve("libs");
        if (libsDir.toFile().exists()) {
            try (Stream<Path> jarFiles = Files.list(libsDir)) {
                jarFiles.filter(path -> path.toString().endsWith(".jar"))
                        .forEach(jarPath -> {
                            if (!processedPaths.contains(jarPath.toString())) {
                                configBuilder.classpath(jarPath.toString());
                                processedPaths.add(jarPath.toString());
                                System.out.println("DEBUG: Added module " + moduleName + " JAR: " + jarPath);
                            }
                        });
            } catch (IOException e) {
                System.err.println("DEBUG: Error scanning libs directory for module " + moduleName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Enhanced test dependencies resolution using project-specific versions and multiple cache locations
     */
    private void addEnhancedTestDependencies(ParserConfig.Builder configBuilder, Path projectRoot, 
                                           Map<String, String> projectDependencies, Set<String> processedPaths) {
        System.out.println("DEBUG: Starting enhanced test dependency resolution...");
        
        // Priority order for cache locations:
        // 1. Project local gradle cache
        // 2. User home gradle cache
        Path[] cacheLocations = {
            projectRoot.resolve(".gradle").resolve("caches").resolve("modules-2").resolve("files-2.1"),
            Paths.get(System.getProperty("user.home")).resolve(".gradle").resolve("caches").resolve("modules-2").resolve("files-2.1")
        };
        
        // Enhanced list of test dependencies with project-specific versions
        String[][] testDeps = {
            {"org.testng", "testng", projectDependencies.getOrDefault("testng", "org.testng:testng:7.7.1")},
            {"junit", "junit", projectDependencies.getOrDefault("junit", "junit:junit:4.13.2")},
            {"org.junit.jupiter", "junit-jupiter-api", "org.junit.jupiter:junit-jupiter-api:5.9.1"},
            {"org.junit.jupiter", "junit-jupiter-engine", "org.junit.jupiter:junit-jupiter-engine:5.9.1"},
            {"org.mockito", "mockito-core", projectDependencies.getOrDefault("mockito", "org.mockito:mockito-core:4.11.0")},
            {"org.hamcrest", "hamcrest-all", projectDependencies.getOrDefault("hamcrest", "org.hamcrest:hamcrest-all:1.3")},
            {"org.hamcrest", "hamcrest-core", "org.hamcrest:hamcrest-core:2.2"},
            {"org.assertj", "assertj-core", projectDependencies.getOrDefault("assertj", "org.assertj:assertj-core:3.20.2")},
            {"com.google.guava", "guava", projectDependencies.getOrDefault("guava", "com.google.guava:guava:33.2.1-jre")},
            {"org.apache.commons", "commons-lang3", "org.apache.commons:commons-lang3:3.9"},
            {"com.google.gson", "gson", "com.google.gson:gson:2.8.9"}
        };
        
        for (String[] dep : testDeps) {
            String groupId = dep[0];
            String artifactId = dep[1];
            String fullDependency = dep[2];
            
            System.out.println("DEBUG: Resolving test dependency: " + groupId + ":" + artifactId + " from " + fullDependency);
            
            // Extract version from full dependency string
            String[] parts = fullDependency.split(":");
            String version = parts.length > 2 ? parts[2] : null;
            
            boolean found = false;
            for (Path gradleCache : cacheLocations) {
                if (addSpecificDependency(configBuilder, gradleCache, groupId, artifactId, version, processedPaths)) {
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                System.out.println("DEBUG: Could not find " + groupId + ":" + artifactId + " in any cache location");
            }
        }
    }
    
    /**
     * Add a specific dependency from gradle cache
     */
    private boolean addSpecificDependency(ParserConfig.Builder configBuilder, Path gradleCache, 
                                        String groupId, String artifactId, String version, Set<String> processedPaths) {
        if (!gradleCache.toFile().exists()) {
            return false;
        }
        
        Path groupPath = gradleCache.resolve(groupId).resolve(artifactId);
        
        if (!groupPath.toFile().exists()) {
            return false;
        }
        
        // If specific version is provided, try that first
        if (version != null) {
            Path versionDir = groupPath.resolve(version);
            if (versionDir.toFile().exists()) {
                File[] hashDirs = versionDir.toFile().listFiles(File::isDirectory);
                if (hashDirs != null) {
                    for (File hashDir : hashDirs) {
                        String expectedJarName = artifactId + "-" + version + ".jar";
                        File jarFile = new File(hashDir, expectedJarName);
                        
                        if (jarFile.exists() && !processedPaths.contains(jarFile.getAbsolutePath())) {
                            configBuilder.classpath(jarFile.getAbsolutePath());
                            processedPaths.add(jarFile.getAbsolutePath());
                            System.out.println("DEBUG: Added specific version dependency: " + jarFile.getAbsolutePath());
                            return true;
                        }
                    }
                }
            }
        }
        
        // Fallback to latest available version
        File[] versionDirs = groupPath.toFile().listFiles(File::isDirectory);
        if (versionDirs == null || versionDirs.length == 0) {
            return false;
        }
        
        // Sort to get the latest version (simple string sort)
        java.util.Arrays.sort(versionDirs, (a, b) -> b.getName().compareTo(a.getName()));
        
        for (File versionDir : versionDirs) {
            File[] hashDirs = versionDir.listFiles(File::isDirectory);
            if (hashDirs != null) {
                for (File hashDir : hashDirs) {
                    String expectedJarName = artifactId + "-" + versionDir.getName() + ".jar";
                    File jarFile = new File(hashDir, expectedJarName);
                    
                    if (jarFile.exists() && !processedPaths.contains(jarFile.getAbsolutePath())) {
                        configBuilder.classpath(jarFile.getAbsolutePath());
                        processedPaths.add(jarFile.getAbsolutePath());
                        System.out.println("DEBUG: Added fallback version dependency: " + jarFile.getAbsolutePath());
                        return true;
                    }
                }
            }
        }
        
        return false;
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

    /**
     * Detect Gradle version from gradle wrapper properties or gradle version command
     */
    private String detectGradleVersion(Path projectRoot) {
        // Check gradle wrapper properties first
        Path gradleWrapperProps = projectRoot.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties");
        if (gradleWrapperProps.toFile().exists()) {
            try {
                List<String> lines = Files.readAllLines(gradleWrapperProps);
                for (String line : lines) {
                    if (line.contains("distributionUrl") && line.contains("gradle-")) {
                        // Extract version from URL like: https://services.gradle.org/distributions/gradle-5.6.4-bin.zip
                        Pattern pattern = Pattern.compile("gradle-(\\d+\\.\\d+(?:\\.\\d+)?)-");
                        Matcher matcher = pattern.matcher(line);
                        if (matcher.find()) {
                            String version = matcher.group(1);
                            System.out.println("DEBUG: Detected Gradle version from wrapper: " + version);
                            return version;
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("DEBUG: Error reading gradle wrapper properties: " + e.getMessage());
            }
        }
        
        // Fallback: assume newer version if wrapper not found
        System.out.println("DEBUG: Could not detect Gradle version, assuming compatible");
        return "7.0"; // Default to a reasonably modern version
    }
    
    /**
     * Check if detected Gradle version is compatible with our Tooling API
     */
    private boolean isGradleToolingApiCompatible(String gradleVersion) {
        try {
            String[] versionParts = gradleVersion.split("\\.");
            int majorVersion = Integer.parseInt(versionParts[0]);
            int minorVersion = versionParts.length > 1 ? Integer.parseInt(versionParts[1]) : 0;
            
            // Our Tooling API is compatible with Gradle 6.0+
            // For older versions (like 5.6.4), use file system detection
            if (majorVersion >= 6) {
                return true;
            } else if (majorVersion == 5 && minorVersion >= 6) {
                // Gradle 5.6+ might work, but let's be conservative
                return false; // Can be changed to true after testing
            }
            
            return false;
        } catch (Exception e) {
            System.err.println("DEBUG: Error parsing Gradle version: " + gradleVersion + ", error: " + e.getMessage());
            return false; // Be conservative on parsing errors
        }
    }
}
