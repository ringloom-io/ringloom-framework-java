// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RingloomFrameworkProcessorTest {
    @TempDir
    Path tempDir;

    @Test
    void generatesClientDispatcherAndApplication() throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        Path generated = Files.createDirectories(tempDir.resolve("generated"));

        boolean success = compile(classes, generated, List.of(
            source("test.OrdersApp", """
                package test;
                import io.ringloom.framework.annotation.RingloomApplication;
                @RingloomApplication(service = "orders")
                public final class OrdersApp {}
                """),
            source("test.PricingClient", """
                package test;
                import io.ringloom.framework.annotation.RingloomClient;
                import io.ringloom.framework.annotation.RingloomRequest;
                import java.lang.foreign.MemorySegment;
                @RingloomClient(service = "pricing")
                public interface PricingClient {
                  @RingloomRequest(templateId = 11)
                  int send(MemorySegment payload);
                }
                """),
            source("test.Handlers", """
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
                """)
        ));

        assertTrue(success);
        assertTrue(Files.exists(generated.resolve("test/PricingClient_RingloomClient.java")));
        assertTrue(Files.exists(generated.resolve("test/OrdersApp_RingloomApplication.java")));
        assertTrue(Files.exists(classes.resolve("META-INF/services/io.ringloom.framework.generated.GeneratedRingloomApplicationProvider")));
    }

    @Test
    void rejectsDuplicateHandlerTemplateIds() throws Exception {
        Path classes = Files.createDirectories(tempDir.resolve("dup-classes"));
        Path generated = Files.createDirectories(tempDir.resolve("dup-generated"));

        boolean success = compile(classes, generated, List.of(source("test.DuplicateHandlers", """
            package test;
            import io.ringloom.framework.annotation.RingloomHandler;
            import io.ringloom.framework.annotation.RingloomServiceComponent;
            @RingloomServiceComponent
            public final class DuplicateHandlers {
              @RingloomHandler(templateId = 7) public int a() { return 0; }
              @RingloomHandler(templateId = 7) public int b() { return 0; }
            }
            """)));

        assertFalse(success);
    }

    private static boolean compile(Path classes, Path generated, List<SimpleJavaFileObject> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        List<String> options = List.of(
            "-classpath", System.getProperty("java.class.path"),
            "-d", classes.toString(),
            "-s", generated.toString(),
            "-processor", RingloomFrameworkProcessor.class.getName()
        );
        boolean success = compiler.getTask(null, null, diagnostics, options, null, sources).call();
        for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                System.err.println(diagnostic);
            }
        }
        return success;
    }

    private static SimpleJavaFileObject source(String className, String content) {
        return new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }
}
