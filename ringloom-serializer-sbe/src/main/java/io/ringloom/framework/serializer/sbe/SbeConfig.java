// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.serializer.sbe;

/**
 * Configuration for locating generated SBE codecs.
 *
 * @param codecPackage the Java package containing the generated codecs
 */
public record SbeConfig(String codecPackage) {}
