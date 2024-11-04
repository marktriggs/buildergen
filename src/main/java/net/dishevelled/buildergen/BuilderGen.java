package net.dishevelled.buildergen;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.Writer;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@SupportedAnnotationTypes({"net.dishevelled.buildergen.RecordBuilder", "net.dishevelled.buildergen.InstanceBuilder"})
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class BuilderGen extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        handleRecords(annotations, roundEnv);
        handleInstances(annotations, roundEnv);

        return true;
    }

    private static String upcase(String s) {
        if (s.isEmpty()) {
            return s;
        } else {
            return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
        }
    }

    private StringBuilder generateCode(String packageName, String builderName, String targetTypeQualifiedName, List<Element> members) {
        StringBuilder code = new StringBuilder();

        code.append(String.format("package %s;\n\n", packageName));

        code.append(String.format("public class %s {\n", builderName));

        int level = 0;

        for (Element enclosed : members) {
            String fieldName = enclosed.getSimpleName().toString();
            String fieldType = enclosed.asType().toString();

            code.append(String.format("  protected %s %s;\n", fieldType, fieldName));

            code.append(String.format("  public %s%d set%s(%s %s) {\n", builderName, level, upcase(fieldName), fieldType, fieldName));
            code.append(String.format("    this.%s = %s;\n", fieldName, fieldName));
            code.append(String.format("    return new %s%d();\n", builderName, level));
            code.append(String.format("  };\n\n"));

            code.append(String.format("  public class %s%d {\n", builderName, level));
            level++;
        }

        code.append(String.format("  public %s build() {\n", targetTypeQualifiedName));
        code.append(String.format("    return new %s(", targetTypeQualifiedName));

        int argCount = 0;
        for (Element enclosed : members) {
            argCount++;
            String fieldName = enclosed.getSimpleName().toString();

            code.append(String.format("%s,", fieldName));
        }

        // Drop the last comma
        if (argCount > 0) {
            code.deleteCharAt(code.length() - 1);
        }

        code.append(String.format(");\n"));
        code.append(String.format("}\n"));

        for (int i = 0; i < level; i++) {
            code.append("}\n");
        }

        code.append("}\n");

        return code;
    }


    private record PackageAndSuffix(String packageName, String builderSuffix) {}

    private PackageAndSuffix findPackageAndSuffix(Element parent) {
        String packageName = "";
        String builderSuffix = "";

        for (;;) {
            if (parent.getKind().equals(ElementKind.CLASS)) {
                builderSuffix = parent.getSimpleName() + builderSuffix;
            } else if (parent.getKind().equals(ElementKind.PACKAGE)) {
                packageName = parent.toString();
                break;
            } else {
                throw new RuntimeException(String.format("Unrecognised type '%s' on parent '%s'",
                                                         parent.getKind(),
                                                         parent));
            }

            parent = parent.getEnclosingElement();
        }

        return new PackageAndSuffix(packageName, builderSuffix);
    }

    public void handleRecords(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(RecordBuilder.class)) {
            if (element.getKind() == ElementKind.RECORD) {
                TypeElement recordElement = (TypeElement) element;

                PackageAndSuffix packageAndSuffix = findPackageAndSuffix(recordElement.getEnclosingElement());

                String recordName = recordElement.getSimpleName().toString();

                String builderName = recordName + "Builder" + packageAndSuffix.builderSuffix();

                StringBuilder code = generateCode(packageAndSuffix.packageName(), builderName, recordElement.getQualifiedName().toString(),
                                                  recordElement.getEnclosedElements().stream().filter(e -> e.getKind() == ElementKind.FIELD).collect(Collectors.toList()));

                try {
                    JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(String.format("%s.%s", packageAndSuffix.packageName(), builderName));
                    try (Writer writer = sourceFile.openWriter()) {
                        writer.write(code.toString());
                    }
                } catch (Exception e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to write file: " + e.getMessage());
                }
            } else {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Can only be applied to records.  You supplied: " + element.getKind(), element);
            }
        }
    }

    public void handleInstances(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(InstanceBuilder.class)) {
            if (element.getKind() == ElementKind.CONSTRUCTOR) {
                ExecutableElement constructorElement = (javax.lang.model.element.ExecutableElement) element;

                TypeElement classElement = (TypeElement)constructorElement.getEnclosingElement();

                PackageAndSuffix packageAndSuffix = findPackageAndSuffix(classElement.getEnclosingElement());

                String className = classElement.getSimpleName().toString();

                String builderName = className + "Builder" + packageAndSuffix.builderSuffix();

                StringBuilder code = generateCode(packageAndSuffix.packageName(), builderName, classElement.getQualifiedName().toString(),
                                                  constructorElement.getParameters().stream().collect(Collectors.toList()));

                try {
                    JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(String.format("%s.%s", packageAndSuffix.packageName(), builderName));
                    try (Writer writer = sourceFile.openWriter()) {
                        writer.write(code.toString());
                    }
                } catch (Exception e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to write file: " + e.getMessage());
                }
            } else {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Can only be applied to constructors.  You supplied: " + element.getKind(), element);
            }
        }
    }
}
