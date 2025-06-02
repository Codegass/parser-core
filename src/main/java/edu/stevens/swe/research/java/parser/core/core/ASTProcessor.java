package edu.stevens.swe.research.java.parser.core.core;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import java.util.Map;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ASTProcessor {
    private ParserConfig config;

    public ASTProcessor(ParserConfig config) {
        this.config = config;
    }

    public void setConfig(ParserConfig config) {
        this.config = config;
    }

    public ASTParser createParser() {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest()); // Use the latest JLS version available
        parser.setResolveBindings(true); // Enable binding resolution

        // Optional: setting the binding recovery as well in case of partial bindings
        // due to missing types or other issues
        parser.setBindingsRecovery(true);
        parser.setIgnoreMethodBodies(false);
        parser.setStatementsRecovery(true);

        //compiler options
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(config.getComplianceLevel(), options);
        parser.setCompilerOptions(options);

        String[] sourcepathEntries = config.getSourcepathEntries();
        String[] encodings = config.getEncodings();

        // If encodings array is provided but its length doesn't match sourcepathEntries length,
        // and it's not a single encoding meant for all, JDT expects null or matching length.
        // If we have a single encoding (our default) and multiple source paths, pass null for encodings
        // to let JDT use the platform default or configured default for all units.
        // Or, ensure encodings array matches sourcepathEntries length if specific encodings per source entry are needed.
        // For simplicity, if source paths exist and encodings list is a single default, pass null to apply default to all.
        String[] effectiveEncodings = null;
        if (sourcepathEntries != null && sourcepathEntries.length > 0 && 
            encodings != null && encodings.length == 1 && 
            sourcepathEntries.length > 1) {
            // We have multiple source paths but only one (default) encoding specified.
            // Passing null tells JDT to use the default encoding for all source path entries.
            effectiveEncodings = null; 
        } else if (sourcepathEntries != null && sourcepathEntries.length > 0 && 
                   encodings != null && encodings.length > 0) {
            // If encodings are provided and we have source paths, use them only if lengths match
            if (encodings.length == sourcepathEntries.length) {
                effectiveEncodings = encodings;
            } else {
                // Length mismatch, use null to let JDT use default encoding
                effectiveEncodings = null;
            }
        } else if (sourcepathEntries == null || sourcepathEntries.length == 0) {
            // No source paths, so no encodings needed
            effectiveEncodings = null;
        }
        // If both sourcepathEntries and encodings are null or empty, effectiveEncodings remains null, which is fine.

        // Prepare classpath entries with safety checks and fallbacks
        String[] classpathEntries = prepareClasspathEntries();

        // Add detailed debugging before setEnvironment call
        System.out.println("DEBUG: About to call setEnvironment with:");
        System.out.println("DEBUG: classpathEntries = " + (classpathEntries == null ? "null" : "array[" + classpathEntries.length + "]"));
        if (classpathEntries != null) {
            for (int i = 0; i < classpathEntries.length; i++) {
                System.out.println("DEBUG:   [" + i + "] = " + classpathEntries[i]);
            }
        }
        System.out.println("DEBUG: sourcepathEntries = " + (sourcepathEntries == null ? "null" : "array[" + sourcepathEntries.length + "]"));
        if (sourcepathEntries != null) {
            for (int i = 0; i < sourcepathEntries.length; i++) {
                System.out.println("DEBUG:   [" + i + "] = " + sourcepathEntries[i]);
            }
        }
        System.out.println("DEBUG: effectiveEncodings = " + (effectiveEncodings == null ? "null" : "array[" + effectiveEncodings.length + "]"));
        if (effectiveEncodings != null) {
            for (int i = 0; i < effectiveEncodings.length; i++) {
                System.out.println("DEBUG:   [" + i + "] = " + effectiveEncodings[i]);
            }
        }

        // Set environment with classpath and sourcepath if available
        parser.setEnvironment(
            classpathEntries,
            sourcepathEntries,
            effectiveEncodings, // Use the potentially adjusted encodings array
            true
        );

        // Configure the parser to parse compilation units (i.e., complete source files)
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        return parser;
    }

    /**
     * Prepares classpath entries with safety checks and fallbacks.
     * Ensures the returned array is not null and contains no null elements.
     * If no classpath entries are available, provides basic JDK libraries as fallback.
     */
    private String[] prepareClasspathEntries() {
        String[] originalClasspath = config.getClasspathEntries();
        List<String> validClasspath = new ArrayList<>();

        // Filter out null entries from original classpath
        if (originalClasspath != null) {
            for (String entry : originalClasspath) {
                if (entry != null && !entry.trim().isEmpty()) {
                    validClasspath.add(entry);
                }
            }
        }

        // If no valid classpath entries found, add basic JDK libraries as fallback
        if (validClasspath.isEmpty()) {
            System.out.println("DEBUG: No classpath entries found, adding JDK libraries as fallback");
            addJdkLibrariesFallback(validClasspath);
        }

        // If still empty after fallback, provide an empty but non-null array
        // JDT can work with empty classpath array
        return validClasspath.toArray(new String[0]);
    }

    /**
     * Adds basic JDK libraries to the classpath as a fallback.
     */
    private void addJdkLibrariesFallback(List<String> classpath) {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path javaHomePath = Paths.get(javaHome);
            
            // Fixed: Use Path.resolve() chain instead of hardcoded path strings for cross-platform compatibility
            // For Java 9+ (modules system)
            Path jrtFs = javaHomePath.resolve("lib").resolve("jrt-fs.jar");
            if (jrtFs.toFile().exists()) {
                classpath.add(jrtFs.toString());
                System.out.println("DEBUG: Added JDK library fallback: " + jrtFs);
                return;
            }
            
            // For Java 8 and earlier
            Path rtJar = javaHomePath.resolve("lib").resolve("rt.jar");
            if (rtJar.toFile().exists()) {
                classpath.add(rtJar.toString());
                System.out.println("DEBUG: Added JDK library fallback: " + rtJar);
                return;
            }
            
            System.out.println("DEBUG: No JDK libraries found in fallback attempt: " + javaHome);
        } else {
            System.out.println("DEBUG: java.home system property not found for JDK libraries fallback");
        }
    }
}
