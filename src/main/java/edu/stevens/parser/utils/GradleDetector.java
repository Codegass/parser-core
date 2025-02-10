package edu.stevens.parser.utils;

import edu.stevens.parser.core.ParserConfig;
import edu.stevens.parser.utils.exceptions.ProjectDetectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.idea.IdeaProject;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency;

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

            // Add compiled classes directory
            Path buildClasses = projectRoot.resolve("build/classes/java/main");
            if (buildClasses.toFile().exists()) {
                configBuilder.classpath(buildClasses.toString());
            }

            // Add resources directory
            Path resources = projectRoot.resolve("src/main/resources");
            if (resources.toFile().exists()) {
                configBuilder.classpath(resources.toString());
            }

            // Add dependencies using Gradle Tooling API
            addGradleDependencies(configBuilder, projectRoot);

            // Add JDK libraries
            addJdkLibraries(configBuilder);

            return configBuilder.build();
        } catch (Exception e) {
            throw new ProjectDetectionException("Failed to detect Gradle project configuration", e);
        }
    }

    private void addGradleDependencies(ParserConfig.Builder configBuilder, Path projectRoot) {
        ProjectConnection connection = null;
        try {
            connection = GradleConnector.newConnector()
                    .forProjectDirectory(projectRoot.toFile())
                    .connect();

            // Get IDEA project model which includes dependency information
            ModelBuilder<IdeaProject> modelBuilder = connection.model(IdeaProject.class);
            IdeaProject project = modelBuilder.get();

            Set<String> processedDeps = new HashSet<>();

            // Process all modules
            for (IdeaModule module : project.getModules()) {
                // Add module dependencies
                module.getDependencies().forEach(dependency -> {
                    if (dependency instanceof IdeaSingleEntryLibraryDependency) {
                        File file = ((IdeaSingleEntryLibraryDependency) dependency).getFile();
                        if (file != null && file.exists() && !processedDeps.contains(file.getAbsolutePath())) {
                            configBuilder.classpath(file.getAbsolutePath());
                            processedDeps.add(file.getAbsolutePath());
                        }
                    }
                });
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
