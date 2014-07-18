package com.baasbox.controllers;

import com.baasbox.controllers.actions.filters.ConnectToDBFilter;
import com.baasbox.controllers.actions.filters.ExtractQueryParameters;
import com.baasbox.controllers.actions.filters.UserOrAnonymousCredentialsFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import org.w3c.dom.Document;
import org.w3c.dom.*;
import play.Logger;
import play.libs.F;
import play.libs.Json;
import play.libs.XML;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.With;

import javax.xml.namespace.NamespaceContext;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.util.Iterator;
import java.util.Map;

import static play.libs.WS.Response;
import static play.libs.WS.url;

public class DataConvert extends Controller {
    private static final String XMLNAMESPACE = "xmlns";

    @With({UserOrAnonymousCredentialsFilter.class, ConnectToDBFilter.class, ExtractQueryParameters.class})
    public static Result toJson() {
        if (Logger.isTraceEnabled()) Logger.trace("Method Start");
        Http.RequestBody body = request().body();
        Map<String, String[]> bodyContent = body.asMultipartFormData().asFormUrlEncoded();
        final JsonNode bodyJson = Json.parse(bodyContent.get("config")[0]);
        if (bodyJson == null) return badRequest("The body payload cannot be empty.");
        String[] files = bodyContent.get("file");
        final Optional<String> fileContent = files != null && files.length > 0 ? Optional.of(files[0]) : Optional.<String>absent();
        ParseHeaderNode urlHeader = new ParseHeaderNode(bodyJson, "url");
        if (!urlHeader.validate()) return badRequest("url field must be a string");
        String sourceUrl = urlHeader.getTextValue();
        ParseHeaderNode rootHeader = new ParseHeaderNode(bodyJson, "root");
        if (!rootHeader.validate()) return badRequest("root field must be a string");
        final String rootXPath = rootHeader.getTextValue();

        if (Logger.isTraceEnabled()) {
            Logger.trace("toJson fieldName: " + sourceUrl);
        }

        Document document;
        if (fileContent.isPresent()) {
            document = XML.fromString(fileContent.get());
        } else {
            F.Promise<Response> responseFuture = url(sourceUrl).get();
            Response response = responseFuture.get();
            document = response.asXml();
        }
        JsonNode someData = convertToJson(document, rootXPath, bodyJson.get("structure"), bodyJson.get("fields"));
        return ok(someData);

    }

    private static JsonNode convertToJson(Document document, String rootXPath, JsonNode structure, JsonNode fields) {
        MyNamespaceContext myNamespaceContext = new MyNamespaceContext(null);

        NamedNodeMap attributes = document.getDocumentElement().getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node currentAttribute = attributes.item(i);
            String currentLocalName = currentAttribute.getLocalName();
            String currentPrefix = currentAttribute.getPrefix();
            String nodeValue = currentAttribute.getNodeValue();
            if (XMLNAMESPACE.equals(currentPrefix)) {
                myNamespaceContext.prefixMap.put(currentLocalName, nodeValue);
            }

        }
        ObjectNode output = Json.newObject();
        ArrayNode data = output.putArray("data");

        javax.xml.xpath.XPath xPath = XPathFactory.newInstance().newXPath();
        xPath.setNamespaceContext(myNamespaceContext);
        try {
            NodeList items = (NodeList) xPath.evaluate(rootXPath, document.getDocumentElement(), XPathConstants.NODESET);
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                ObjectNode converted = Json.newObject();
                if (structure != null) {
                    Iterator<Map.Entry<String, JsonNode>> structureFields = structure.fields();
                    while (structureFields.hasNext()) {
                        Map.Entry<String, JsonNode> next = structureFields.next();
                        String value = (String) xPath.evaluate(next.getValue().asText(), item, XPathConstants.STRING);
                        converted.put(next.getKey(), value);
                    }
                }

                for (JsonNode field : fields) {
                    try {
                        if (field.get("type") == null)
                            continue;
                        switch (field.get("type").asText()) {

                            case "number":
                                Double value = (double) xPath.evaluate(field.get("path").asText(), item, XPathConstants.NUMBER);
                                if (value != null && !Double.isNaN(value))
                                    converted.put(field.get("value").asText(), value);
                                else
                                    converted.put(field.get("value").asText(), 0d);
                                break;
                            case "date":
                                String dateString = (String) xPath.evaluate(field.get("path").asText(), item, XPathConstants.STRING);
                                converted.put(field.get("value").asText(), dateString);
                                break;
                            case "text":
                            default:
                                String text = (String) xPath.evaluate(field.get("path").asText(), item, XPathConstants.STRING);
                                converted.put(field.get("value").asText(), text);
                                break;
                        }


                    } catch (XPathExpressionException e) {
                        converted.put(field.get("value").asText(), "Non trouvé");
                        //Normal : invalidpath
                    }
                }

                data.add(converted);
            }
        } catch (XPathExpressionException e) {
            e.printStackTrace();
        }

        return data;
    }

    static public class MyNamespaceContext implements NamespaceContext {
        final private Map<String, String> prefixMap = Maps.newHashMap();

        MyNamespaceContext(Map<String, String> prefixMap) {

        }

        public String getPrefix(String namespaceURI) {
            // TODO Auto-generated method stub
            return null;
        }

        public Iterator getPrefixes(String namespaceURI) {
            // TODO Auto-generated method stub
            return null;
        }

        public String getNamespaceURI(String prefix) {
            if (prefixMap.containsKey(prefix))
                return prefixMap.get(prefix);
            else
                return "";
        }
    }

    private static class ParseHeaderNode {
        private final String fieldName;
        private boolean hasErrors;
        private JsonNode bodyJson;
        private String textValue;

        public ParseHeaderNode(JsonNode bodyJson, String name) {
            this.bodyJson = bodyJson;
            fieldName = name;
        }

        boolean valid() {
            return !hasErrors;
        }

        public String getTextValue() {
            return textValue;
        }

        public ParseHeaderNode invoke() {
            JsonNode url = bodyJson.get(this.fieldName);
            if (url != null && !url.isTextual()) {
                hasErrors = true;
                return this;
            }
            textValue = url.asText();
            hasErrors = false;
            return this;
        }

        public boolean validate() {
            invoke();
            return valid();
        }
    }
}
