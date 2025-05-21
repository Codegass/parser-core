package edu.stevens.swe.research.java.parser.core.utils;

import edu.stevens.swe.research.java.parser.core.core.ParserConfig;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractBuildToolDetector implements ProjectDetector {
    protected static final String DEFAULT_ENCODING = "UTF-8";
    protected static final String DEFAULT_COMPLIANCE_LEVEL = "17"; // Can be configured later

    /**
     * Get JDK home directory path
     */
    protected String getJavaHome() {
        return System.getProperty("java.home");
    }

    /**
     * Get user home directory path
     */
    protected String getUserHome() {
        return System.getProperty("user.home");
    }

    /**
     * Check if a file exists at the given path
     */
    protected boolean fileExists(Path path, String filename) {
        return Files.exists(path.resolve(filename));
    }

    /**
     * Find all files with the given name recursively starting from root
     */
    protected List<Path> findFilesRecursively(Path root, String filename) {
        List<Path> result = new ArrayList<>();
        File rootDir = root.toFile();
        if (!rootDir.isDirectory()) {
            return result;
        }

        File[] files = rootDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    result.addAll(findFilesRecursively(file.toPath(), filename));
                } else if (file.getName().equals(filename)) {
                    result.add(file.toPath());
                }
            }
        }
        return result;
    }

    /**
     * Create a basic ParserConfig builder with common settings
     */
    protected ParserConfig.Builder createBaseConfig() {
        return new ParserConfig.Builder()
                .encodings(DEFAULT_ENCODING)
                .complianceLevel(DEFAULT_COMPLIANCE_LEVEL);
    }
}
