package edu.stevens.swe.research.java.parser.core.utils;

import edu.stevens.swe.research.java.parser.core.core.ParserConfig;
import edu.stevens.swe.research.java.parser.core.utils.exceptions.ProjectDetectionException;

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
        System.out.println("DEBUG: BuildToolDetectorFactory.detect() called for project: " + projectRoot);
        for (ProjectDetector detector : detectors) {
            System.out.println("DEBUG: Trying detector: " + detector.getClass().getSimpleName());
            if (detector.supports(projectRoot)) {
                System.out.println("DEBUG: Using detector: " + detector.getClass().getSimpleName() + " for project: " + projectRoot);
                return detector.detect(projectRoot);
            } else {
                System.out.println("DEBUG: Detector " + detector.getClass().getSimpleName() + " does not support project: " + projectRoot);
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
