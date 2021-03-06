/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.protocol.http.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.regex.Pattern;

import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.config.GraphQLRequestParams;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Utilities to (de)serialize GraphQL request parameters.
 */
public final class GraphQLRequestParamUtils {

    private static Logger log = LoggerFactory.getLogger(GraphQLRequestParamUtils.class);

    private static Pattern WHITESPACES_PATTERN = Pattern.compile("\\p{Space}+");

    private GraphQLRequestParamUtils() {
    }

    /**
     * Return true if the content type is GraphQL content type (i.e. 'application/json').
     * @param contentType Content-Type value
     * @return true if the content type is GraphQL content type
     */
    public static boolean isGraphQLContentType(final String contentType) {
        if (StringUtils.isEmpty(contentType)) {
            return false;
        }
        final ContentType type = ContentType.parse(contentType);
        return ContentType.APPLICATION_JSON.getMimeType().equals(type.getMimeType());
    }

    /**
     * Convert the GraphQL request parameters input data to an HTTP POST body string.
     * @param params GraphQL request parameter input data
     * @return an HTTP POST body string converted from the GraphQL request parameters input data
     * @throws RuntimeException if JSON serialization fails for some reason due to any runtime environment issues
     */
    public static String toPostBodyString(final GraphQLRequestParams params) {
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode postBodyJson = mapper.createObjectNode();
        postBodyJson.put("operationName", StringUtils.trimToNull(params.getOperationName()));

        if (StringUtils.isNotBlank(params.getVariables())) {
            try {
                final ObjectNode variablesJson = mapper.readValue(params.getVariables(), ObjectNode.class);
                postBodyJson.set("variables", variablesJson);
            } catch (JsonProcessingException e) {
                log.error("Ignoring the GraphQL query variables content due to the syntax error: {}",
                        e.getLocalizedMessage());
            }
        }

        postBodyJson.put("query", StringUtils.trim(params.getQuery()));

        try {
            return mapper.writeValueAsString(postBodyJson);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot serialize JSON for POST body string", e);
        }
    }

    /**
     * Convert the GraphQL Query input string into an HTTP GET request parameter value.
     * @param query the GraphQL Query input string
     * @return an HTTP GET request parameter value converted from the GraphQL Query input string
     */
    public static String queryToGetParamValue(final String query) {
        return RegExUtils.replaceAll(StringUtils.trim(query), WHITESPACES_PATTERN, " ");
    }

    /**
     * Convert the GraphQL Variables JSON input string into an HTTP GET request parameter value.
     * @param variables the GraphQL Variables JSON input string
     * @return an HTTP GET request parameter value converted from the GraphQL Variables JSON input string
     */
    public static String variablesToGetParamValue(final String variables) {
        final ObjectMapper mapper = new ObjectMapper();

        try {
            final ObjectNode variablesJson = mapper.readValue(variables, ObjectNode.class);
            return mapper.writeValueAsString(variablesJson);
        } catch (JsonProcessingException e) {
            log.error("Ignoring the GraphQL query variables content due to the syntax error: {}",
                    e.getLocalizedMessage());
        }

        return null;
    }

    /**
     * Parse {@code postData} and convert it to a {@link GraphQLRequestParams} object if it is a valid GraphQL post data.
     * @param postData post data
     * @param contentEncoding content encoding
     * @return a converted {@link GraphQLRequestParams} object form the {@code postData}
     * @throws IllegalArgumentException if {@code postData} is not a GraphQL post JSON data or not a valid JSON
     * @throws JsonProcessingException if it fails to serialize a parsed JSON object to string
     * @throws UnsupportedEncodingException if it fails to decode parameter value
     */
    public static GraphQLRequestParams toGraphQLRequestParams(byte[] postData, final String contentEncoding)
            throws IllegalArgumentException, JsonProcessingException, UnsupportedEncodingException {
        final String encoding = StringUtils.isNotEmpty(contentEncoding) ? contentEncoding
                : EncoderCache.URL_ARGUMENT_ENCODING;

        final ObjectMapper mapper = new ObjectMapper();
        ObjectNode data;

        try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(postData), encoding)) {
            data = mapper.readValue(reader, ObjectNode.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid json data: " + e.getLocalizedMessage());
        }

        String operationName = null;
        String query = null;
        String variables = null;

        final JsonNode operationNameNode = data.has("operationName") ? data.get("operationName") : null;
        if (operationNameNode != null) {
            operationName = getJsonNodeTextContent(operationNameNode, true);
        }

        if (!data.has("query")) {
            throw new IllegalArgumentException("Not a valid GraphQL query.");
        }
        final JsonNode queryNode = data.get("query");
        query = getJsonNodeTextContent(queryNode, false);
        final String trimmedQuery = StringUtils.trim(query);
        if (!StringUtils.startsWith(trimmedQuery, "query") && !StringUtils.startsWith(trimmedQuery, "mutation")) {
            throw new IllegalArgumentException("Not a valid GraphQL query.");
        }

        final JsonNode variablesNode = data.has("variables") ? data.get("variables") : null;
        if (variablesNode != null) {
            final JsonNodeType nodeType = variablesNode.getNodeType();
            if (nodeType != JsonNodeType.NULL) {
                if (nodeType == JsonNodeType.OBJECT) {
                    variables = mapper.writeValueAsString(variablesNode);
                } else {
                    throw new IllegalArgumentException("Not a valid object node for GraphQL variables.");
                }
            }
        }

        return new GraphQLRequestParams(operationName, query, variables);
    }

    /**
     * Parse {@code arguments} and convert it to a {@link GraphQLRequestParams} object if it has valid GraphQL HTTP arguments.
     * @param arguments arguments
     * @param contentEncoding content encoding
     * @return a converted {@link GraphQLRequestParams} object form the {@code arguments}
     * @throws IllegalArgumentException if {@code arguments} does not contain valid GraphQL request arguments
     * @throws UnsupportedEncodingException if it fails to decode parameter value
     */
    public static GraphQLRequestParams toGraphQLRequestParams(final Arguments arguments, final String contentEncoding)
            throws IllegalArgumentException, UnsupportedEncodingException {
        final String encoding = StringUtils.isNotEmpty(contentEncoding) ? contentEncoding
                : EncoderCache.URL_ARGUMENT_ENCODING;

        String operationName = null;
        String query = null;
        String variables = null;

        for (JMeterProperty prop : arguments) {
            final Argument arg = (Argument) prop.getObjectValue();
            if (!(arg instanceof HTTPArgument)) {
                continue;
            }

            final String name = arg.getName();
            final String metadata = arg.getMetaData();
            final String value = StringUtils.trimToNull(arg.getValue());

            if ("=".equals(metadata) && value != null) {
                final boolean alwaysEncoded = ((HTTPArgument) arg).isAlwaysEncoded();

                if ("operationName".equals(name)) {
                    operationName = alwaysEncoded ? value : URLDecoder.decode(value, encoding);
                } else if ("query".equals(name)) {
                    query = alwaysEncoded ? value : URLDecoder.decode(value, encoding);
                } else if ("variables".equals(name)) {
                    variables = alwaysEncoded ? value : URLDecoder.decode(value, encoding);
                }
            }
        }

        if (StringUtils.isEmpty(query)
                || (!StringUtils.startsWith(query, "query") && !StringUtils.startsWith(query, "mutation"))) {
            throw new IllegalArgumentException("Not a valid GraphQL query.");
        }

        if (StringUtils.isNotEmpty(variables)) {
            if (!StringUtils.startsWith(variables, "{") || !StringUtils.endsWith(variables, "}")) {
                throw new IllegalArgumentException("Not a valid object node for GraphQL variables.");
            }
        }

        return new GraphQLRequestParams(operationName, query, variables);
    }

    private static String getJsonNodeTextContent(final JsonNode jsonNode, final boolean nullable) throws IllegalArgumentException {
        final JsonNodeType nodeType = jsonNode.getNodeType();

        if (nodeType == JsonNodeType.NULL) {
            if (nullable) {
                return null;
            }

            throw new IllegalArgumentException("Not a non-null value node.");
        }

        if (nodeType == JsonNodeType.STRING) {
            return jsonNode.asText();
        }

        throw new IllegalArgumentException("Not a string value node.");
    }
}
