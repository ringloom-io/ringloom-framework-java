// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

final class ProcessorTestSupport {

    static final String PRICING_CLIENT_SRC = """
                    package test;
                    import io.ringloom.framework.annotation.RingloomClient;
                    import io.ringloom.framework.annotation.RingloomRequest;
                    import java.lang.foreign.MemorySegment;
                    @RingloomClient(service = "pricing")
                    public interface PricingClient {
                      @RingloomRequest(templateId = 11)
                      int send(MemorySegment payload);
                    }
                    """;

    static final String ORDERS_APP_SRC = """
                    package test;
                    import io.ringloom.framework.annotation.RingloomApplication;
                    @RingloomApplication(service = "orders")
                    public final class OrdersApp {}
                    """;

    static final String HANDLERS_SRC = """
                    package test;
                    import io.ringloom.framework.annotation.RingloomHandler;
                    import io.ringloom.framework.annotation.RingloomServiceComponent;
                    import io.ringloom.framework.dispatch.MessageContext;
                    import java.lang.foreign.MemorySegment;
                    @RingloomServiceComponent
                    public final class Handlers {
                      @RingloomHandler(templateId = 12)
                      public int onMessage(MemorySegment payload, MessageContext context) { return 0; }
                    }
                    """;

    static boolean compile(Path classes, Path generated, List<SimpleJavaFileObject> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        List<String> options = List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString(),
                "-s",
                generated.toString(),
                "-processor",
                RingloomFrameworkProcessor.class.getName());
        boolean success = compiler.getTask(null, null, diagnostics, options, null, sources)
                .call();
        for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                System.err.println(diagnostic);
            }
        }
        return success;
    }

    static SimpleJavaFileObject source(String className, String content) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + className.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }
}
