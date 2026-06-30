// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.model;

public record SbePayloadModel(
        String dtoType, String encoderType, String decoderType, boolean dtoPayload, boolean decoderPayload) {

    public static SbePayloadModel from(String payloadType) {
        if (payloadType.endsWith("Dto")) {
            String baseType = payloadType.substring(0, payloadType.length() - 3);
            return new SbePayloadModel(payloadType, baseType + "Encoder", baseType + "Decoder", true, false);
        }
        if (payloadType.endsWith("Decoder")) {
            String baseType = payloadType.substring(0, payloadType.length() - 7);
            return new SbePayloadModel(baseType + "Dto", baseType + "Encoder", payloadType, false, true);
        }
        return null;
    }

    public static boolean usesSbe(String serializer, String payloadType) {
        return (Symbols.SBE_SERIALIZER.equals(serializer)
                || (serializer.isBlank() && SbePayloadModel.from(payloadType) != null));
    }
}
