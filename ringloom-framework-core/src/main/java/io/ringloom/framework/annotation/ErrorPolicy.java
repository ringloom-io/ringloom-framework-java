// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

/**
 * Controls how generated client code reports send and request failures back to callers.
 */
public enum ErrorPolicy {
    STATUS,
    THROWING
}
