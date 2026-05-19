// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.ioc.avaje;

import io.avaje.inject.BeanScope;
import io.ringloom.framework.RingloomApplicationRunner;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Convenience bootstrap for RingLoom applications that use Avaje Inject-managed components.
 */
public final class AvajeRingloomBootstrap {

    private final RingloomAvajeConfig config;

    private AvajeRingloomBootstrap(RingloomAvajeConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Creates an Avaje bootstrap from a YAML configuration path.
     *
     * @param path the RingLoom YAML configuration path
     * @return an Avaje bootstrap configured from the supplied path
     */
    public static AvajeRingloomBootstrap fromYaml(Path path) {
        Objects.requireNonNull(path, "path");
        return new AvajeRingloomBootstrap(RingloomAvajeConfig.fromPath(path.toString()));
    }

    /**
     * Creates an Avaje bootstrap from a YAML configuration path.
     *
     * @param path the RingLoom YAML configuration path
     * @return an Avaje bootstrap configured from the supplied path
     */
    public static AvajeRingloomBootstrap fromYaml(String path) {
        return fromYaml(Path.of(Objects.requireNonNull(path, "path")));
    }

    /**
     * Starts the Avaje scope, RingLoom runtime, and configured event loops.
     *
     * <p>The returned runner owns the Avaje {@link BeanScope}; closing the runner also closes the
     * scope.
     *
     * @return the running application
     */
    public RingloomApplicationRunner start() {
        BeanScope scope = BeanScope.builder()
                .bean(RingloomAvajeConfig.class, config)
                .modules(new RingloomAvajeModule())
                .build();
        RingloomApplicationRunner runner = scope.get(RingloomApplicationRunner.class);
        return new RingloomApplicationRunner(runner.runtime(), false, "ringloom-avaje", scope);
    }
}
