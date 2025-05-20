package edu.stevens.swe.research.parser.core.visitor;

import edu.stevens.swe.research.parser.core.model.TestCaseInfo;
import org.eclipse.jdt.core.dom.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TestAstVisitor extends ASTVisitor {
    private final List<TestCaseInfo> testCases = new ArrayList<>();
    private String currentClassName = null;
    private final String filePath;

    public TestAstVisitor(String filePath) {
        this.filePath = new File(filePath).getAbsolutePath(); // Ensure absolute path
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        // Consider only public top-level classes or public static nested classes
        if (Modifier.isPublic(node.getModifiers())) {
            if (node.isPackageMemberTypeDeclaration()) { // Top-level class
                currentClassName = node.getName().getFullyQualifiedName();
            } else if (node.isMemberTypeDeclaration() && Modifier.isStatic(node.getModifiers())) { // Static nested class
                // For nested classes, we might want to construct the fully qualified name
                // This simplistic approach gets the simple name. For full qualification, need parent info.
                String parentName = getParentClassName(node);
                currentClassName = (parentName != null ? parentName + "." : "") + node.getName().getFullyQualifiedName();
            } else {
                currentClassName = null; // Skip non-public or non-static nested classes as primary test holders
            }
        } else {
            currentClassName = null;
        }
        return super.visit(node);
    }

    private String getParentClassName(TypeDeclaration node) {
        ASTNode parent = node.getParent();
        while (parent != null) {
            if (parent instanceof TypeDeclaration) {
                return ((TypeDeclaration) parent).getName().getFullyQualifiedName();
            }
            // Could add EnumDeclaration, etc., if tests can be nested there
            parent = parent.getParent();
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean visit(MethodDeclaration node) {
        if (currentClassName != null && Modifier.isPublic(node.getModifiers()) && !node.isConstructor()) {
            List<IExtendedModifier> modifiers = node.modifiers();
            for (IExtendedModifier extendedModifier : modifiers) {
                if (extendedModifier.isAnnotation()) {
                    Annotation annotation = (Annotation) extendedModifier;
                    String annotationName = annotation.getTypeName().getFullyQualifiedName();
                    if ("Test".equals(annotationName) || "org.junit.Test".equals(annotationName) || "org.junit.jupiter.api.Test".equals(annotationName) || "org.testng.annotations.Test".equals(annotationName)) {
                        String methodName = node.getName().getIdentifier();
                        testCases.add(new TestCaseInfo(currentClassName, methodName, this.filePath));
                        break; // Found a recognized @Test annotation
                    }
                }
            }
        }
        return super.visit(node);
    }

    public List<TestCaseInfo> getTestCases() {
        return testCases;
    }
} 