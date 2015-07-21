/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.security.helpers;

import com.emc.storageos.security.helpers.SecurityService;
import com.emc.storageos.security.ssh.PEMUtil;

import java.security.*;
import java.security.interfaces.RSAPrivateKey;

public class DefaultSecurityService implements SecurityService {

    private String[] ciphers;

    @Override
    public byte[] loadPrivateKeyFromPEMString(String pemKey) throws Exception {

        if (! PEMUtil.isPKCS8Key(pemKey)) {
            throw new Exception("Only PKCS8 is supported");
        }

        return PEMUtil.decodePKCS8PrivateKey(pemKey);
    }

    @Override
    public void clearSensitiveData(byte[] key) {

    }

    @Override
    public void clearSensitiveData(Key rsaPrivateKey) {

    }

    @Override
    public void clearSensitiveData(KeyPair keyPair) {

    }

    @Override
    public void clearSensitiveData(Signature signatureFactory) {

    }

    @Override
    public void clearSensitiveData(KeyPairGenerator keyGen) {

    }

    @Override
    public void clearSensitiveData(SecureRandom random) {

    }

    @Override
    public void initSecurityProvider() {

    }

    @Override
    public String[] getCipherSuite() {
        return ciphers;
    }

    public void setCiphers(String[] ciphers) {
        this.ciphers = ciphers;
    }
}
