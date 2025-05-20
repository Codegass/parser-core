package edu.stevens.swe.research.parser.core.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ParserConfig {
    private final List<String> classpathEntries = new ArrayList<>();
    private final List<String> sourcepathEntries = new ArrayList<>();
    private final List<String> encodings = new ArrayList<>();
    private String complianceLevel;

    // Builder pattern implementation
    public static class Builder {
        private final ParserConfig config = new ParserConfig();

        public Builder classpath(String... entries) {
            config.classpathEntries.addAll(Arrays.asList(entries));
            return this;
        }

        public Builder sourcepath(String... entries) {
            config.sourcepathEntries.addAll(Arrays.asList(entries));
            return this;
        }

        public Builder encodings(String... encodings) {
            config.encodings.addAll(Arrays.asList(encodings));
            return this;
        }

        public Builder complianceLevel(String level) {
            config.complianceLevel = level;
            return this;
        }

        public ParserConfig build() {
            return config;
        }
    }

    // Getters
    public String[] getClasspathEntries() { return classpathEntries.toArray(new String[0]); }
    public String[] getSourcepathEntries() { return sourcepathEntries.toArray(new String[0]); }
    public String[] getEncodings() { return encodings.toArray(new String[0]); }
    public String getComplianceLevel() { return complianceLevel; }
}
