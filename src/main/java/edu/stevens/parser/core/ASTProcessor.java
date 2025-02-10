package edu.stevens.parser.core;

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

        // Set environment with classpath and sourcepath if available
        parser.setEnvironment(
            config.getClasspathEntries(),
            config.getSourcepathEntries(),
            config.getEncodings(),
            true
        );

        // Configure the parser to parse compilation units (i.e., complete source files)
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        return parser;
    }
}
