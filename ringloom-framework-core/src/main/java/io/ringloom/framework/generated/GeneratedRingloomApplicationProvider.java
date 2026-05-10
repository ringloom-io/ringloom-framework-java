// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.generated;

/**
 * Service-provider entry point used to discover generated application metadata via
 * {@link java.util.ServiceLoader}.
 */
public interface GeneratedRingloomApplicationProvider {
    /**
     * Returns the generated application metadata provided by this service.
     *
     * @return the generated application metadata
     */
    GeneratedRingloomApplication application();
}
