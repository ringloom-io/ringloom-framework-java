// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Loads a {@link RingloomApplicationConfig} from an external configuration source.
 */
public interface RingloomConfigLoader {
    /**
     * Returns whether this loader knows how to read the given path.
     *
     * @param path the candidate configuration path
     * @return {@code true} when the loader can process the path
     */
    boolean supports(Path path);

    /**
     * Reads a RingLoom application configuration from the supplied path.
     *
     * @param path the configuration file to read
     * @return the parsed application configuration
     * @throws IOException if the source cannot be read
     */
    RingloomApplicationConfig load(Path path) throws IOException;
}
