// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.ioc.avaje;

/**
 * Configuration for the Avaje IoC adapter.
 *
 * @param autoStart whether the RingLoom runtime should be started while the Avaje scope is built
 * @param startEventLoops whether managed RingLoom event loops should start after runtime startup
 * @param registerGeneratedClients whether generated client interface beans should be registered
 * @param configPath optional configuration file path used when no programmatic config bean exists
 */
public record RingloomAvajeConfig(
        boolean autoStart, boolean startEventLoops, boolean registerGeneratedClients, String configPath) {
    /**
     * Avaje property key used to locate a RingLoom configuration file.
     */
    public static final String CONFIG_PATH_PROPERTY = "ringloom.config.path";

    public RingloomAvajeConfig {
        configPath = configPath == null || configPath.isBlank() ? null : configPath;
        if (!autoStart && (startEventLoops || registerGeneratedClients)) {
            throw new IllegalArgumentException("startEventLoops and registerGeneratedClients require autoStart");
        }
    }

    /**
     * Returns the default eager Avaje adapter configuration.
     *
     * @return eager startup settings
     */
    public static RingloomAvajeConfig defaults() {
        return new RingloomAvajeConfig(true, true, true, null);
    }

    /**
     * Returns lazy settings suitable for tests that should not start native RingLoom services.
     *
     * @return lazy startup settings
     */
    public static RingloomAvajeConfig lazy() {
        return new RingloomAvajeConfig(false, false, false, null);
    }

    /**
     * Returns eager settings that load the application config from a file path.
     *
     * @param configPath the RingLoom config path
     * @return eager settings with a config path
     */
    public static RingloomAvajeConfig fromPath(String configPath) {
        return new RingloomAvajeConfig(true, true, true, configPath);
    }
}
