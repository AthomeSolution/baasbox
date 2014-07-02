package com.baasbox.controllers.hooks;

import com.baasbox.service.storage.AssetService;
import com.google.common.base.Strings;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.db.ODatabaseComplex;
import com.orientechnologies.orient.core.db.ODatabaseLifecycleListener;
import com.orientechnologies.orient.core.hook.ODocumentHookAbstract;
import com.orientechnologies.orient.core.record.impl.ODocument;
import play.Logger;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.Arrays;

public class Image extends ODocumentHookAbstract implements ODatabaseLifecycleListener {
    private static final String FIELD_IMG = "img";

    public Image() {
        Logger.trace("REGISTERING HOOK");
        Orient.instance().addDbLifecycleListener(this);
    }

    @Override
    public void onCreate(ODatabase iDatabase) {
        ((ODatabaseComplex<?>) iDatabase).registerHook(this);
    }

    @Override
    public void onOpen(ODatabase iDatabase) {
        ((ODatabaseComplex<?>) iDatabase).registerHook(this);
    }

    @Override
    public void onClose(ODatabase iDatabase) {
        ((ODatabaseComplex<?>) iDatabase).unregisterHook(this);
    }

    @Override
    public DISTRIBUTED_EXECUTION_MODE getDistributedExecutionMode() {
        return DISTRIBUTED_EXECUTION_MODE.SOURCE_NODE;
    }

    @Override
    public RESULT onRecordBeforeUpdate(ODocument iDocument) {
        if (!Arrays.asList(iDocument.fieldNames()).contains(FIELD_IMG))
            return RESULT.RECORD_NOT_CHANGED;

        String sUrl = iDocument.field(FIELD_IMG);
        if (Strings.isNullOrEmpty(sUrl) || sUrl.startsWith("/asset/"))
            return RESULT.RECORD_NOT_CHANGED;

        if (!sUrl.startsWith("http"))
            sUrl = "http://" + sUrl;

        String name = null;
        try {
            name = getAssetName(iDocument, FIELD_IMG);
            ImageDownloader imageDL = new ImageDownloader(sUrl);

            if (Logger.isInfoEnabled())
                Logger.info("Saving image to assets: " + sUrl);

            imageDL.download();
            if (AssetService.getByName(name) != null)
                AssetService.deleteByName(name);
            AssetService.createFile(
                    name,
                    imageDL.getFilename(),
                    null,
                    imageDL.getContentType(),
                    imageDL.getContent()
            );

            iDocument.field(FIELD_IMG, "/asset/" + name);
            return RESULT.RECORD_CHANGED;
        } catch (Throwable throwable) {
            Logger.error("Failed to save asset: " + name, throwable);
            return RESULT.RECORD_NOT_CHANGED;
        }
    }

    @Override
    public void onRecordAfterDelete(ODocument iDocument) {
        if (!Arrays.asList(iDocument.fieldNames()).contains(FIELD_IMG)
                || Strings.isNullOrEmpty(String.valueOf(iDocument.field(FIELD_IMG))))
            return;

        String name = null;
        try {
            name = getAssetName(iDocument, FIELD_IMG);
            if (Logger.isInfoEnabled())
                Logger.info("Deleting image from assets: " + name);
            if (AssetService.getByName(name) != null)
                AssetService.deleteByName(name);
        } catch (Throwable throwable) {
            Logger.error("Failed to delete asset: " + name, throwable);
        }
    }

    private static String getAssetName(ODocument doc, String fieldName) throws UnsupportedEncodingException {
        return doc.field("id") + URLEncoder.encode(fieldName, "UTF-8");
    }

    private static final class ImageDownloader {
        private final URL url;
        private String contentType;
        private byte[] content;

        public ImageDownloader(String sUrl) throws MalformedURLException {
            url = new URL(sUrl);
        }

        public String getContentType() {
            return contentType;
        }

        public String getFilename() {
            String name = url.getFile();
            if (name.startsWith("/"))
                name = name.substring(1);
            return name;
        }

        public byte[] getContent() {
            return content;
        }

        public URL getURL() {
            return url;
        }

        public byte[] download() throws IOException {
            InputStream in = new BufferedInputStream(this.url.openStream());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;

            contentType = URLConnection.guessContentTypeFromStream(in);
            while (-1 != (n = in.read(buf))) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
            content = out.toByteArray();
            return content;
        }
    }
}
