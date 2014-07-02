package com.baasbox.controllers;

import com.baasbox.controllers.actions.filters.ConnectToDBFilter;
import com.baasbox.controllers.actions.filters.UserOrAnonymousCredentialsFilter;
import com.baasbox.service.storage.AssetService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.io.Files;
import com.orientechnologies.orient.core.record.impl.ODocument;
import play.Logger;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.With;

import java.util.Map;

public class UploadController extends Controller {
    @With({UserOrAnonymousCredentialsFilter.class, ConnectToDBFilter.class})
    public static Result upload() {

        response().setHeader("Access-Control-Allow-Origin", "*");
        response().setHeader("Access-Control-Allow-Headers", "X-Requested-With");

        final Http.MultipartFormData form = request().body().asMultipartFormData();
        final Map<String,String[]> stringMap = form.asFormUrlEncoded();
        Http.MultipartFormData.FilePart part = form.getFile("file");

        try {
            String filename = stringMap.get("key")[0];
            final ODocument doc = AssetService.createFile(
                    filename,
                    part.getFilename(),
                    null,
                    part.getContentType(),
                    Files.toByteArray(part.getFile())
            );
            final String fileName = doc.field("name");
            final ObjectNode returnJson = Json.newObject();
            returnJson.put("link", "http://localhost:9000/asset/" + fileName); // FIXME address
            response().getHeaders().put("nowrap", "true");
            return ok(returnJson);
        } catch (Throwable throwable) {
            Logger.error("Failed to upload", throwable);
            return badRequest();
        }
    }
}
