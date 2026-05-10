// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

/**
 * Declares how generated clients should select a target service instance.
 */
public enum RoutingMode {
    LOAD_BALANCED,
    DIRECT,
    LEADER
}
