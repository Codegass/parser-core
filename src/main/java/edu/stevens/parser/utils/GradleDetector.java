package edu.stevens.parser.utils;

import edu.stevens.parser.core.ParserConfig;
import edu.stevens.parser.utils.exceptions.ProjectDetectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ModelBuilder;
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
    private static final String GRADLE_CACHE_PATH = ".gradle/caches/modules-2/files-2.1";

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
            connection = GradleConnector.newConnector()
                    .forProjectDirectory(projectRoot.toFile())
                    .connect();

            // Get IDEA project model for source directories and some dependency info
            IdeaProject ideaProject = connection.model(IdeaProject.class).get();
            Set<String> processedPaths = new HashSet<>();

            for (IdeaModule module : ideaProject.getModules()) {
                // Add module source directories (main and test)
                for (IdeaContentRoot contentRoot : module.getContentRoots()) {
                    for (IdeaSourceDirectory sourceDir : contentRoot.getSourceDirectories()) {
                        File dirFile = sourceDir.getDirectory();
                        if (dirFile.exists() && !processedPaths.contains(dirFile.getAbsolutePath())) {
                            configBuilder.sourcepath(dirFile.getAbsolutePath());
                            processedPaths.add(dirFile.getAbsolutePath());
                        }
                    }
                    // It seems IdeaSourceDirectory doesn't distinguish well between source/resource or main/test easily by type
                    // We might need to rely on path conventions or use EclipseProject model for more details
                }

                // Add module dependencies (JARs)
                // This gets all library dependencies, doesn't easily distinguish test/compile scope with IdeaProject alone
                module.getDependencies().forEach(dependency -> {
                    if (dependency instanceof IdeaSingleEntryLibraryDependency) {
                        File file = ((IdeaSingleEntryLibraryDependency) dependency).getFile();
                        if (file != null && file.exists() && file.getName().endsWith(".jar") && !processedPaths.contains(file.getAbsolutePath())) {
                            configBuilder.classpath(file.getAbsolutePath());
                            processedPaths.add(file.getAbsolutePath());
                        }
                    }
                });
            }

            // Use EclipseProject model for more detailed classpath and output locations (main and test)
            EclipseProject eclipseProject = connection.model(EclipseProject.class).get();
            for (EclipseSourceDirectory eclipseSourceDir : eclipseProject.getSourceDirectories()) {
                File dirFile = eclipseSourceDir.getDirectory();
                if (dirFile.exists() && !processedPaths.contains(dirFile.getAbsolutePath())) {
                    configBuilder.sourcepath(dirFile.getAbsolutePath());
                    processedPaths.add(dirFile.getAbsolutePath());
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
                    }
                }
            }
            // Note: EclipseProject model might not explicitly separate test output location easily without custom config.
            // Common paths for Gradle output are added below as a fallback/addition.
            Path buildClassesMain = projectRoot.resolve("build/classes/java/main");
            if (buildClassesMain.toFile().exists() && !processedPaths.contains(buildClassesMain.toString())) {
                configBuilder.classpath(buildClassesMain.toString());
                processedPaths.add(buildClassesMain.toString());
            }
            Path buildClassesTest = projectRoot.resolve("build/classes/java/test");
            if (buildClassesTest.toFile().exists() && !processedPaths.contains(buildClassesTest.toString())) {
                configBuilder.classpath(buildClassesTest.toString());
                processedPaths.add(buildClassesTest.toString());
            }
            Path buildResourcesMain = projectRoot.resolve("build/resources/main");
            if (buildResourcesMain.toFile().exists() && !processedPaths.contains(buildResourcesMain.toString())) {
                configBuilder.classpath(buildResourcesMain.toString());
                processedPaths.add(buildResourcesMain.toString());
            }
            Path buildResourcesTest = projectRoot.resolve("build/resources/test");
            if (buildResourcesTest.toFile().exists() && !processedPaths.contains(buildResourcesTest.toString())) {
                configBuilder.classpath(buildResourcesTest.toString());
                processedPaths.add(buildResourcesTest.toString());
            }

            // Add classpath entries from Eclipse model (these are usually resolved JARs and project dependencies)
            for (EclipseClasspathEntry cpEntry : eclipseProject.getClasspath()) {
                if (cpEntry instanceof EclipseExternalDependency) {
                    EclipseExternalDependency externalDep = (EclipseExternalDependency) cpEntry;
                    File file = externalDep.getFile();
                    if (file != null && file.exists() && file.getName().endsWith(".jar") && !processedPaths.contains(file.getAbsolutePath())) {
                        configBuilder.classpath(file.getAbsolutePath());
                        processedPaths.add(file.getAbsolutePath());
                    }
                } else if (cpEntry instanceof EclipseProjectDependency) {
                    // For inter-project dependencies, we might need to find their output path.
                    // The EclipseProjectDependency model gives access to the depended-on project.
                    // For simplicity now, we rely on the general output paths being added.
                    // A more robust solution would inspect the linked EclipseProjectDependency.getProject().getOutputLocation()
                }
                // Other types of ClasspathEntry could exist (e.g., source folders, containers like JRE)
                // but for JARs and project outputs, ExternalDependency and ProjectDependency are key.
            }

        } finally {
            if (connection != null) {
                connection.close();
            }
        }
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
}
