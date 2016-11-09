/*
 * Copyright (c) 2016 EMC Corporation
 * All Rights Reserved
 */
package com.emc.vipr.client.core;

import java.io.File;

import javax.ws.rs.core.MediaType;

import com.emc.storageos.model.storagedriver.StorageDriverList;
import com.emc.vipr.client.core.impl.PathConstants;
import com.emc.vipr.client.impl.RestClient;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.multipart.FormDataMultiPart;
import com.sun.jersey.multipart.file.FileDataBodyPart;

public class StorageDriver {

    private RestClient client;
    public StorageDriver(RestClient client) {
        this.client = client;
    }

    public StorageDriverList getDrivers() {
        return client.get(StorageDriverList.class, PathConstants.STORAGE_DRIVER_LIST_URL);
    }

    public ClientResponse installDriver(File f) {
        FormDataMultiPart multiPart = new FormDataMultiPart();
        multiPart.bodyPart(new FileDataBodyPart("driver", f, MediaType.APPLICATION_OCTET_STREAM_TYPE));
        return client.postMultiPart(ClientResponse.class, multiPart, PathConstants.STORAGE_DRIVER_INSTALL_URL);
    }

    public ClientResponse uninstallDriver(String driverName) {
        return client.delete(ClientResponse.class, PathConstants.STORAGE_DRIVER_UNINSTALL_URL, driverName);
    }

    public ClientResponse upgradeDriver(String driverName, File driverFile) {
        FormDataMultiPart multiPart = new FormDataMultiPart();
        multiPart.bodyPart(new FileDataBodyPart("driver", driverFile, MediaType.APPLICATION_OCTET_STREAM_TYPE));
        return client.postMultiPart(ClientResponse.class, multiPart, PathConstants.STORAGE_DRIVER_UPGRADE_URL, driverName);
    }
}
