package edu.stevens.parser.utils;

import edu.stevens.parser.core.ParserConfig;
import edu.stevens.parser.utils.exceptions.ProjectDetectionException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BuildToolDetectorFactory {
    private static final List<ProjectDetector> detectors = new ArrayList<>();

    static {
        // Register available detectors
        detectors.add(new MavenDetector());
        detectors.add(new GradleDetector());
    }

    /**
     * Detect the build tool and configuration for the given project root
     * @param projectRoot The root directory of the project
     * @return ParserConfig containing the detected configuration
     * @throws ProjectDetectionException if no suitable build tool is found or detection fails
     */
    public static ParserConfig detect(Path projectRoot) throws ProjectDetectionException {
        for (ProjectDetector detector : detectors) {
            if (detector.supports(projectRoot)) {
                return detector.detect(projectRoot);
            }
        }
        throw new ProjectDetectionException("No supported build tool found in: " + projectRoot);
    }

    /**
     * Add a custom detector to the factory
     * @param detector The detector to add
     */
    public static void addDetector(ProjectDetector detector) {
        detectors.add(detector);
    }
}
