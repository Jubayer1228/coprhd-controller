/*
 * Copyright (c) 2017 EMC Corporation
 * All Rights Reserved
 */
package com.emc.vipr.model.sys.diagutil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.Serializable;

@XmlRootElement(name = "upload_param")
public class UploadParam implements Serializable {
    private static final Logger log = LoggerFactory.getLogger(UploadParam.class);
    private UploadType uploadType;
    private UploadFtpParam uploadFtpParam;

    @XmlElement(name = "upload_type")
    public UploadType getUploadType() {
        return uploadType;
    }

    public void setUploadType(UploadType uploadType) {
        this.uploadType = uploadType;
    }



    public UploadParam() {}

    @XmlElement(name = "ftp_param")
    public UploadFtpParam getUploadFtpParam() {
        return uploadFtpParam;
    }

    public void setUploadFtpParam(UploadFtpParam uploadFtpParam) {
        this.uploadFtpParam = uploadFtpParam;
    }

    public static enum UploadType {
        download,
        ftp,
        sftp
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("upload Type {");
        sb.append(uploadType);
        sb.append("}");
        sb.append("ftp param {");
        sb.append(uploadFtpParam.toString());
        sb.append("}");

        return sb.toString();
    }

}
