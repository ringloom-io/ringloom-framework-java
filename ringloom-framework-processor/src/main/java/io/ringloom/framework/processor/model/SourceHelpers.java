// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

public final class SourceHelpers {

    private SourceHelpers() {}

    public static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String packageName(Elements elements, TypeElement type) {
        PackageElement pkg = elements.getPackageOf(type);
        return pkg.isUnnamed() ? "" : pkg.getQualifiedName().toString();
    }

    public static String serviceName(TypeElement application, String fallback) {
        io.ringloom.framework.annotation.RingloomApplication annotation =
                application.getAnnotation(io.ringloom.framework.annotation.RingloomApplication.class);
        if (annotation == null || annotation.service().isBlank()) {
            return fallback;
        }
        return annotation.service();
    }

    public static List<TypeElement> types(Set<? extends Element> elements) {
        List<TypeElement> result = new ArrayList<>();
        for (Element element : elements) {
            if (element instanceof TypeElement typeElement) {
                result.add(typeElement);
            }
        }
        return result;
    }

    public static boolean returnsInt(ExecutableElement method) {
        return method.getReturnType().toString().equals("int");
    }
}
