/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */
package org.gluu.oxtrust.service.antlr.scimFilter.util;

import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializerProvider;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;
import org.gluu.oxtrust.model.scim2.User;
import org.gluu.oxtrust.model.scim2.schema.extension.UserExtensionSchema;
import org.gluu.oxtrust.service.scim2.jackson.custom.UserSerializer;
import org.gluu.oxtrust.service.scim2.schema.SchemaTypeMapping;
import org.jboss.seam.annotations.Logger;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.log.Log;

import java.io.IOException;
import java.util.*;

/**
 * @author Val Pecaoco
 */
@Name("listResponseUserSerializer")
public class ListResponseUserSerializer extends UserSerializer {

    @Logger
    private static Log log;

    private String attributesArray;
    private String[] attributes;

    @Override
    public void serialize(User user, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {

        log.info(" serialize() ");

        try {

            jsonGenerator.writeStartObject();

            ObjectMapper mapper = new ObjectMapper();
            mapper.disable(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES);

            attributes = (attributesArray != null && !attributesArray.isEmpty()) ? mapper.readValue(attributesArray, String[].class) : null;

            JsonNode rootNode = mapper.convertValue(user, JsonNode.class);

            processNodes(null, rootNode, mapper, user, jsonGenerator);

            jsonGenerator.writeEndObject();

        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException("Unexpected processing error; please check the input parameters.");
        }
    }

    /*
     * This is a recursive method to completely process all the nodes
     */
    private void processNodes(String parent, JsonNode rootNode, ObjectMapper mapper, User user, JsonGenerator jsonGenerator) throws Exception {

        // log.info(" ##### PARENT: " + parent);

        Iterator<Map.Entry<String, JsonNode>> iterator = rootNode.getFields();

        while (iterator.hasNext()) {

            Map.Entry<String, JsonNode> rootNodeEntry = iterator.next();

            if (attributes != null && attributes.length > 0) {

                for (String attribute : attributes) {

                    String[] split = attribute.split("\\.");

                    if ((((parent != null && !parent.isEmpty()) && parent.equalsIgnoreCase(split[0])) && rootNodeEntry.getKey().equalsIgnoreCase(split[1])) ||
                            rootNodeEntry.getKey().equalsIgnoreCase(split[0])) {

                        // log.info(" ##### MATCH: " + attribute);

                        if ((rootNodeEntry.getValue() instanceof ObjectNode || rootNodeEntry.getValue() instanceof ArrayNode) &&
                                !(SchemaTypeMapping.getSchemaTypeInstance(rootNodeEntry.getKey()) instanceof UserExtensionSchema)) {

                            jsonGenerator.writeFieldName(rootNodeEntry.getKey());

                            jsonGenerator.writeStartObject();

                            if (rootNodeEntry.getValue() instanceof ObjectNode) {

                                processNodes(rootNodeEntry.getKey(), rootNodeEntry.getValue(), mapper, user, jsonGenerator);  // Recursion

                            } else if (rootNodeEntry.getValue() instanceof ArrayNode) {

                                ArrayNode arrayNode = (ArrayNode) rootNodeEntry.getValue();

                                for (int i = 0; i < arrayNode.size(); i++) {

                                    JsonNode arrayNodeElement = arrayNode.get(i);
                                    processNodes(rootNodeEntry.getKey(), arrayNodeElement, mapper, user, jsonGenerator);  // Recursion
                                }
                            }

                            jsonGenerator.writeEndObject();

                        } else {

                            jsonGenerator.writeFieldName(rootNodeEntry.getKey());

                            if (SchemaTypeMapping.getSchemaTypeInstance(rootNodeEntry.getKey()) instanceof UserExtensionSchema) {

                                serializeUserExtension(rootNodeEntry, mapper, user, jsonGenerator);

                            } else {

                                jsonGenerator.writeObject(rootNodeEntry.getValue());
                            }
                        }

                        break;
                    }
                }

            } else {

                if (rootNodeEntry.getValue() instanceof ObjectNode && !(SchemaTypeMapping.getSchemaTypeInstance(rootNodeEntry.getKey()) instanceof UserExtensionSchema)) {

                    jsonGenerator.writeFieldName(rootNodeEntry.getKey());

                    jsonGenerator.writeStartObject();
                    processNodes(rootNodeEntry.getKey(), rootNodeEntry.getValue(), mapper, user, jsonGenerator);  // Recursion
                    jsonGenerator.writeEndObject();

                } else {

                    jsonGenerator.writeFieldName(rootNodeEntry.getKey());

                    if (SchemaTypeMapping.getSchemaTypeInstance(rootNodeEntry.getKey()) instanceof UserExtensionSchema) {

                        serializeUserExtension(rootNodeEntry, mapper, user, jsonGenerator);

                    } else {

                        jsonGenerator.writeObject(rootNodeEntry.getValue());
                    }
                }
            }
        }
    }

    public void setAttributesArray(String attributesArray) {
        this.attributesArray = attributesArray;
    }
}