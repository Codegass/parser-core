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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

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

            // Add dependencies and source/resource paths using Gradle Tooling API
            addGradleProjectDetails(configBuilder, projectRoot);

            // Add JDK libraries
            addJdkLibraries(configBuilder);

            return configBuilder.build();
        } catch (Exception e) {
            throw new ProjectDetectionException("Failed to detect Gradle project configuration", e);
        }
    }

    private void addGradleProjectDetails(ParserConfig.Builder configBuilder, Path projectRoot) {
        ProjectConnection connection = null;
        try {
            System.out.println("DEBUG: Connecting to Gradle project using Tooling API...");
            connection = GradleConnector.newConnector()
                    .forProjectDirectory(projectRoot.toFile())
                    .connect();

            // Get IDEA project model for source directories and some dependency info
            IdeaProject ideaProject = connection.model(IdeaProject.class).get();
            Set<String> processedPaths = new HashSet<>();
            
            System.out.println("DEBUG: Found " + ideaProject.getModules().size() + " Gradle modules");

            for (IdeaModule module : ideaProject.getModules()) {
                System.out.println("DEBUG: Processing module: " + module.getName());
                
                // Add module source directories (main and test)
                for (IdeaContentRoot contentRoot : module.getContentRoots()) {
                    for (IdeaSourceDirectory sourceDir : contentRoot.getSourceDirectories()) {
                        File dirFile = sourceDir.getDirectory();
                        if (dirFile.exists() && !processedPaths.contains(dirFile.getAbsolutePath())) {
                            configBuilder.sourcepath(dirFile.getAbsolutePath());
                            processedPaths.add(dirFile.getAbsolutePath());
                            System.out.println("DEBUG: Added source directory: " + dirFile.getAbsolutePath());
                        }
                    }
                    // It seems IdeaSourceDirectory doesn't distinguish well between source/resource or main/test easily by type
                    // We might need to rely on path conventions or use EclipseProject model for more details
                }

                // Add module dependencies (JARs)
                // This gets all library dependencies, doesn't easily distinguish test/compile scope with IdeaProject alone
                System.out.println("DEBUG: Processing " + module.getDependencies().size() + " dependencies for module: " + module.getName());
                module.getDependencies().forEach(dependency -> {
                    if (dependency instanceof IdeaSingleEntryLibraryDependency) {
                        File file = ((IdeaSingleEntryLibraryDependency) dependency).getFile();
                        if (file != null && file.exists() && file.getName().endsWith(".jar") && !processedPaths.contains(file.getAbsolutePath())) {
                            configBuilder.classpath(file.getAbsolutePath());
                            processedPaths.add(file.getAbsolutePath());
                            System.out.println("DEBUG: Added dependency JAR: " + file.getAbsolutePath());
                        } else if (file != null && !file.exists()) {
                            System.out.println("DEBUG: Dependency JAR not found: " + file.getAbsolutePath());
                        }
                    }
                });
            }

            // Use EclipseProject model for more detailed classpath and output locations (main and test)
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

            // Add main and test compile output directories from Eclipse model
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
            // Note: EclipseProject model might not explicitly separate test output location easily without custom config.
            // Common paths for Gradle output are added below as a fallback/addition.
            Path buildClassesMain = projectRoot.resolve("build").resolve("classes").resolve("java").resolve("main");
            if (buildClassesMain.toFile().exists() && !processedPaths.contains(buildClassesMain.toString())) {
                configBuilder.classpath(buildClassesMain.toString());
                processedPaths.add(buildClassesMain.toString());
                System.out.println("DEBUG: Added Gradle main classes directory: " + buildClassesMain);
            }
            Path buildClassesTest = projectRoot.resolve("build").resolve("classes").resolve("java").resolve("test");
            if (buildClassesTest.toFile().exists() && !processedPaths.contains(buildClassesTest.toString())) {
                configBuilder.classpath(buildClassesTest.toString());
                processedPaths.add(buildClassesTest.toString());
                System.out.println("DEBUG: Added Gradle test classes directory: " + buildClassesTest);
            }
            Path buildResourcesMain = projectRoot.resolve("build").resolve("resources").resolve("main");
            if (buildResourcesMain.toFile().exists() && !processedPaths.contains(buildResourcesMain.toString())) {
                configBuilder.classpath(buildResourcesMain.toString());
                processedPaths.add(buildResourcesMain.toString());
                System.out.println("DEBUG: Added Gradle main resources directory: " + buildResourcesMain);
            }
            Path buildResourcesTest = projectRoot.resolve("build").resolve("resources").resolve("test");
            if (buildResourcesTest.toFile().exists() && !processedPaths.contains(buildResourcesTest.toString())) {
                configBuilder.classpath(buildResourcesTest.toString());
                processedPaths.add(buildResourcesTest.toString());
                System.out.println("DEBUG: Added Gradle test resources directory: " + buildResourcesTest);
            }

            // Add classpath entries from Eclipse model (these are usually resolved JARs and project dependencies)
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
                    // For inter-project dependencies, we might need to find their output path.
                    // The EclipseProjectDependency model gives access to the depended-on project.
                    // For simplicity now, we rely on the general output paths being added.
                    // A more robust solution would inspect the linked EclipseProjectDependency.getProject().getOutputLocation()
                    EclipseProjectDependency projectDep = (EclipseProjectDependency) cpEntry;
                    System.out.println("DEBUG: Found Eclipse project dependency: " + projectDep.getPath());
                }
                // Other types of ClasspathEntry could exist (e.g., source folders, containers like JRE)
                // but for JARs and project outputs, ExternalDependency and ProjectDependency are key.
            }

            // Add common test dependencies as fallback
            System.out.println("DEBUG: Adding common test dependencies as fallback for Gradle project...");
            addCommonTestDependenciesForGradle(configBuilder, processedPaths);

        } catch (Exception e) {
            System.err.println("DEBUG: Error using Gradle Tooling API: " + e.getMessage());
            System.err.println("DEBUG: Falling back to common test dependencies only...");
            // Fallback: add common test dependencies from Gradle cache
            addCommonTestDependenciesForGradle(configBuilder, new HashSet<>());
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    /**
     * Adds common test dependencies for Gradle projects by searching in Gradle's cache directory.
     * This is a fallback mechanism when Gradle Tooling API fails or misses some dependencies.
     */
    private void addCommonTestDependenciesForGradle(ParserConfig.Builder configBuilder, Set<String> processedPaths) {
        // Gradle cache is typically in ~/.gradle/caches/modules-2/files-2.1/
        Path userHome = Paths.get(System.getProperty("user.home"));
        Path gradleCache = userHome.resolve(".gradle").resolve("caches").resolve("modules-2").resolve("files-2.1");
        
        if (!gradleCache.toFile().exists()) {
            System.out.println("DEBUG: Gradle cache directory not found: " + gradleCache);
            return;
        }
        
        System.out.println("DEBUG: Searching for common test dependencies in Gradle cache: " + gradleCache);
        
        // Common test dependencies to search for
        String[][] commonTestDeps = {
            {"org.junit.jupiter", "junit-jupiter-api"},
            {"org.junit.jupiter", "junit-jupiter-engine"},
            {"org.junit.jupiter", "junit-jupiter-params"},
            {"org.mockito", "mockito-core"},
            {"org.hamcrest", "hamcrest"},
            {"org.assertj", "assertj-core"}
        };
        
        for (String[] dep : commonTestDeps) {
            String groupId = dep[0];
            String artifactId = dep[1];
            
            try {
                findAndAddGradleDependency(configBuilder, gradleCache, groupId, artifactId, processedPaths);
            } catch (Exception e) {
                System.out.println("DEBUG: Error searching for " + groupId + ":" + artifactId + " in Gradle cache: " + e.getMessage());
            }
        }
    }
    
    /**
     * Searches for a dependency in Gradle cache and adds the latest version found to classpath.
     */
    private void findAndAddGradleDependency(ParserConfig.Builder configBuilder, Path gradleCache, String groupId, String artifactId, Set<String> processedPaths) {
        Path groupPath = gradleCache.resolve(groupId).resolve(artifactId);
        
        if (!groupPath.toFile().exists()) {
            System.out.println("DEBUG: No versions found for " + groupId + ":" + artifactId + " in Gradle cache");
            return;
        }
        
        File[] versionDirs = groupPath.toFile().listFiles(File::isDirectory);
        if (versionDirs == null || versionDirs.length == 0) {
            System.out.println("DEBUG: No version directories found for " + groupId + ":" + artifactId);
            return;
        }
        
        // Sort to get the latest version (simple string sort)
        java.util.Arrays.sort(versionDirs, (a, b) -> b.getName().compareTo(a.getName()));
        
        for (File versionDir : versionDirs) {
            // In Gradle cache, the structure is: groupId/artifactId/version/hash/artifactId-version.jar
            File[] hashDirs = versionDir.listFiles(File::isDirectory);
            if (hashDirs != null) {
                for (File hashDir : hashDirs) {
                    String expectedJarName = artifactId + "-" + versionDir.getName() + ".jar";
                    File jarFile = new File(hashDir, expectedJarName);
                    
                    if (jarFile.exists() && !processedPaths.contains(jarFile.getAbsolutePath())) {
                        configBuilder.classpath(jarFile.getAbsolutePath());
                        processedPaths.add(jarFile.getAbsolutePath());
                        System.out.println("DEBUG: Added common test dependency from Gradle cache: " + jarFile.getAbsolutePath());
                        return; // Found and added, no need to check more versions
                    }
                }
            }
        }
        
        System.out.println("DEBUG: No JAR files found for " + groupId + ":" + artifactId + " in Gradle cache");
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
}
