package edu.stevens.parser.utils;

import edu.stevens.parser.core.ParserConfig;
import edu.stevens.parser.utils.exceptions.ProjectDetectionException;
import java.nio.file.Path;

public interface ProjectDetector {
    ParserConfig detect(Path projectRoot) throws ProjectDetectionException;
    boolean supports(Path projectRoot);
}
