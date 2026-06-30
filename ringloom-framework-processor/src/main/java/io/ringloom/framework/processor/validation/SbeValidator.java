// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.processor.validation;

import io.ringloom.framework.processor.ProcessorContext;
import io.ringloom.framework.processor.model.SbePayloadModel;
import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;

public final class SbeValidator {

    private SbeValidator() {}

    public static void validateClientPayload(VariableElement parameter, Elements elements, ProcessorContext ctx) {
        requireSbeDtoPayload(parameter.asType().toString(), parameter, elements, ctx);
    }

    public static void validateHandlerPayload(VariableElement parameter, Elements elements, ProcessorContext ctx) {
        requireSbePayload(parameter.asType().toString(), parameter, elements, ctx);
    }

    public static SbePayloadModel requireSbeDtoPayload(
            String payloadType, Element origin, Elements elements, ProcessorContext ctx) {
        SbePayloadModel payload = requireSbePayload(payloadType, origin, elements, ctx);
        if (!payload.dtoPayload()) {
            ctx.error(origin, "SBE client payload type must be a generated *Dto type: " + payloadType);
        }
        requireTypeExists(origin, elements, payload.encoderType(), "missing generated SBE encoder type ", ctx);
        return payload;
    }

    public static SbePayloadModel requireSbePayload(
            String payloadType, Element origin, Elements elements, ProcessorContext ctx) {
        SbePayloadModel payload = SbePayloadModel.from(payloadType);
        if (payload == null) {
            ctx.error(origin, "unsupported SBE payload type; expected generated *Dto or *Decoder type: " + payloadType);
            return new SbePayloadModel(payloadType, payloadType + "Encoder", payloadType + "Decoder", false, true);
        }
        requireTypeExists(origin, elements, payload.decoderType(), "missing generated SBE decoder type ", ctx);
        if (payload.dtoPayload()) {
            requireTypeExists(origin, elements, payload.dtoType(), "missing generated SBE DTO type ", ctx);
        }
        return payload;
    }

    public static void requireTypeExists(
            Element origin, Elements elements, String typeName, String prefix, ProcessorContext ctx) {
        if (elements.getTypeElement(typeName) == null) {
            ctx.error(origin, prefix + typeName);
        }
    }
}
