/*
 * Copyright (c) 2017 Dell EMC
 * All Rights Reserved
 */
package com.emc.sa.service.hpux.tasks;

import com.emc.hpux.command.ExtendFilesystemCommand;

/**
 * HP-UX task for extending a filesystem device
 * 
 */
public class ExtendFilesystem extends HpuxExecutionTask<Void> {

    private String device;

    public ExtendFilesystem(String device) {
        this.device = device;
    }

    @Override
    public void execute() throws Exception {
        try {
            ExtendFilesystemCommand command = new ExtendFilesystemCommand(device);
            executeCommand(command, SHORT_TIMEOUT);
        } catch (Exception ex) {
            logWarn("hpux.extendfs.fail", ex.getMessage());
        }
    }
}
