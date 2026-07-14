// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Starts and stops a topics-enabled test broker for framework integration tests.
 *
 * <p>This harness mirrors {@code io.ringloom.service.TestBroker} from the bindings test sources but
 * lives in the framework module so topic integration tests can run here. It is activated by setting
 * the {@code ringloom.brokerBin} and {@code ringloom.repoRoot} system properties (typically pointing
 * at a built ringloom broker binary and the ringloom native repo root that contains
 * {@code scripts/start-test-broker.sh}). When the broker binary is not available, tests gated on
 * {@code @EnabledIfSystemProperty(named = "ringloom.brokerBin", matches = ".+")} are skipped.
 */
public final class TestBroker implements AutoCloseable {
    private final ProcessHandle process;
    private final Path repoRoot;
    private final Path workspace;
    private final short nodeId;
    private final String storagePath;
    private final String group;
    private boolean closed;

    private TestBroker(
            ProcessHandle process, Path repoRoot, Path workspace, short nodeId, String storagePath, String group) {
        this.process = process;
        this.repoRoot = repoRoot;
        this.workspace = workspace;
        this.nodeId = nodeId;
        this.storagePath = storagePath;
        this.group = group;
    }

    /**
     * Starts a single-node topics-enabled broker.
     *
     * @param repoRoot  the ringloom repo root containing {@code scripts/start-test-broker.sh}
     * @param workspace a clean workspace directory the broker may use
     * @return the started broker
     * @throws IOException          if the broker process fails to start
     * @throws InterruptedException if the launch wait is interrupted
     */
    public static TestBroker startTopicsEnabled(Path repoRoot, Path workspace)
            throws IOException, InterruptedException {
        return start(repoRoot, workspace, (short) 1, 19001, "ringloom-framework-test", true);
    }

    /** Starts a single-node broker with explicit topics flag. */
    public static TestBroker start(
            Path repoRoot, Path workspace, short nodeId, int port, String group, boolean topicsEnabled)
            throws IOException, InterruptedException {
        Path brokerBin = Path.of(System.getProperty("ringloom.brokerBin"));
        ArrayList<String> command = new ArrayList<>();
        command.add("bash");
        command.add(repoRoot.resolve("scripts/start-test-broker.sh").toString());
        command.add("--workspace");
        command.add(workspace.toString());
        command.add("--node-id");
        command.add(Short.toString(nodeId));
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--group");
        command.add(group);
        command.add("--daemon");
        command.add("--bin-dir");
        command.add(brokerBin.getParent().toString());
        if (topicsEnabled) {
            command.add("--topics");
        }

        Process process = new ProcessBuilder(command)
                .directory(repoRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("failed to start test broker:\n" + output);
        }

        Map<String, String> env = parseEnv(output);
        return new TestBroker(
                process.toHandle(),
                repoRoot,
                workspace,
                Short.parseShort(env.get("RINGLOOM_BROKER_NODE_ID")),
                env.get("RINGLOOM_STORAGE_PATH"),
                env.get("RINGLOOM_GROUP"));
    }

    public short nodeId() {
        return nodeId;
    }

    public String storagePath() {
        return storagePath;
    }

    public String group() {
        return group;
    }

    @Override
    public void close() throws IOException, InterruptedException {
        if (closed) {
            return;
        }
        closed = true;
        Process process = new ProcessBuilder(
                        "bash",
                        repoRoot.resolve("scripts/start-test-broker.sh").toString(),
                        "--workspace",
                        workspace.toString(),
                        "--node-id",
                        Short.toString(nodeId),
                        "--port",
                        Integer.toString(19001),
                        "--group",
                        group,
                        "--stop")
                .directory(repoRoot.toFile())
                .redirectErrorStream(true)
                .start();
        new String(process.getInputStream().readAllBytes());
        process.waitFor();
    }

    private static Map<String, String> parseEnv(String output) throws IOException {
        Map<String, String> env = new HashMap<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.StringReader(output))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                if (key.startsWith("RINGLOOM_")) {
                    env.put(key, line.substring(eq + 1).trim());
                }
            }
        }
        if (!env.containsKey("RINGLOOM_BROKER_NODE_ID")) {
            throw new IOException("broker did not report node id; output:\n" + output);
        }
        return env;
    }
}
