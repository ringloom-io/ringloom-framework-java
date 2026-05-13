// SPDX-License-Identifier: Apache-2.0
package io.ringloom.samples.orders.common;

import io.avaje.inject.BeanScope;
import io.ringloom.framework.ioc.avaje.RingloomAvajeConfig;
import io.ringloom.framework.ioc.avaje.RingloomAvajeModule;
import java.nio.file.Path;

/**
 * Shared Avaje bootstrap for the sample.
 */
public final class AvajeRingloom {

    private AvajeRingloom() {}

    public static BeanScope start(Path configPath) {
        return BeanScope.builder()
                .bean(RingloomAvajeConfig.class, RingloomAvajeConfig.fromPath(configPath.toString()))
                .modules(new RingloomAvajeModule())
                .build();
    }
}
