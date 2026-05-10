// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.config;

import java.io.IOException;
import java.nio.file.Path;

public interface RingloomConfigLoader {
    boolean supports(Path path);

    RingloomApplicationConfig load(Path path) throws IOException;
}
