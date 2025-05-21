package edu.stevens.swe.research.java.parser.core.utils;

import edu.stevens.swe.research.java.parser.core.core.ParserConfig;
import edu.stevens.swe.research.java.parser.core.utils.exceptions.ProjectDetectionException;

import java.nio.file.Path;

public interface ProjectDetector {
    ParserConfig detect(Path projectRoot) throws ProjectDetectionException;
    boolean supports(Path projectRoot);
}
