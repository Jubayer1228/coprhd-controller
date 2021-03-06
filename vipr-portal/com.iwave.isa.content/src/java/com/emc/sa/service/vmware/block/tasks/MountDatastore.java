/*
 * Copyright 2016 EMC Corporation
 * All Rights Reserved
 */
package com.emc.sa.service.vmware.block.tasks;

import java.rmi.RemoteException;

import com.emc.sa.service.vmware.tasks.RetryableTask;
import com.iwave.ext.vmware.HostStorageAPI;
import com.iwave.ext.vmware.VMWareException;
import com.vmware.vim25.HostFileSystemMountInfo;
import com.vmware.vim25.HostFileSystemVolume;
import com.vmware.vim25.HostVmfsVolume;
import com.vmware.vim25.QuiesceDatastoreIOForHAFailed;
import com.vmware.vim25.ResourceInUse;
import com.vmware.vim25.mo.Datastore;
import com.vmware.vim25.mo.HostSystem;

public class MountDatastore extends RetryableTask<Void> {
    private final HostSystem host;
    private final Datastore datastore;

    public MountDatastore(HostSystem host, Datastore datastore) {
        this.host = host;
        this.datastore = datastore;
        provideDetailArgs(datastore.getName(), host.getName());
    }

    @Override
    protected Void tryExecute() {
        final String dataStoreName = datastore.getName();
        for (HostFileSystemMountInfo mount : new HostStorageAPI(host)
                .getStorageSystem().getFileSystemVolumeInfo().getMountInfo()) {

            if (mount != null) {
                HostFileSystemVolume mountVolume = mount.getVolume();

                if (mountVolume == null) {
                    warn("No volume attached to mount : " + mount.getMountInfo().getPath());
                    continue;
                }

                if (mount.getVolume() != null && mount.getVolume() instanceof HostVmfsVolume
                        && dataStoreName.equals(mount.getVolume().getName())) {
                    HostVmfsVolume volume = (HostVmfsVolume) mountVolume;
                    String vmfsUuid = volume.getUuid();
                    info("Mounting volume : " + vmfsUuid);
                    try {
                        new HostStorageAPI(host).getStorageSystem().mountVmfsVolume(vmfsUuid);
                    } catch (RemoteException e) {
                        throw new VMWareException(e);
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected boolean canRetry(VMWareException e) {
        return e.getCause() instanceof QuiesceDatastoreIOForHAFailed || e.getCause() instanceof ResourceInUse;
    }
}
