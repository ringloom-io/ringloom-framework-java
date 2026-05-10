// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.annotation;

/**
 * Declares how a generated client method treats a RingLoom request.
 */
public enum RequestMode {
    ONE_WAY,
    CALLBACK,
    VIRTUAL_THREAD_BLOCKING
}
