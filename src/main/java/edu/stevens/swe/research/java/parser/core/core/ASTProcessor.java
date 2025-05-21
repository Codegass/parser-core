package edu.stevens.swe.research.java.parser.core.core;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import java.util.Map;

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
        } else if (encodings != null && encodings.length > 0) {
            // If encodings are provided and match (or single source path and single encoding), use them.
            // This also covers the case where sourcepathEntries is null/empty but encodings were set (though less common).
            effectiveEncodings = encodings;
        }
        // If both sourcepathEntries and encodings are null or empty, effectiveEncodings remains null, which is fine.

        // Set environment with classpath and sourcepath if available
        parser.setEnvironment(
            config.getClasspathEntries(),
            sourcepathEntries,
            effectiveEncodings, // Use the potentially adjusted encodings array
            true
        );

        // Configure the parser to parse compilation units (i.e., complete source files)
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        return parser;
    }
}
