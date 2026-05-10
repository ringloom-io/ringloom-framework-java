// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

/**
 * Configuration for locating generated SBE codecs.
 *
 * @param codecPackage the Java package containing the generated codecs
 */
public record SbeConfig(String codecPackage) {
    public SbeConfig {
        codecPackage = codecPackage == null ? "" : codecPackage;
    }

    /**
     * Returns the default SBE serializer configuration.
     *
     * @return the default SBE settings
     */
    public static SbeConfig defaults() {
        return new SbeConfig("");
    }
}
