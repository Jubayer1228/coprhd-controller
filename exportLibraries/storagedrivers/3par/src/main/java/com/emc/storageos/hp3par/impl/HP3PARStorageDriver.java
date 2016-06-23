
/*
 * Copyright 2016 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.hp3par.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang.mutable.MutableBoolean;
import org.apache.commons.lang.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.hp3par.command.CPGCommandResult;
import com.emc.storageos.hp3par.command.CPGMember;
import com.emc.storageos.hp3par.command.ConsistencyGroupResult;
import com.emc.storageos.hp3par.command.ConsistencyGroupsListResult;
import com.emc.storageos.hp3par.command.FcPath;
import com.emc.storageos.hp3par.command.HostCommandResult;
import com.emc.storageos.hp3par.command.HostMember;
import com.emc.storageos.hp3par.command.ISCSIPath;
import com.emc.storageos.hp3par.command.PortCommandResult;
import com.emc.storageos.hp3par.command.PortMembers;
import com.emc.storageos.hp3par.command.PortStatMembers;
import com.emc.storageos.hp3par.command.PortStatisticsCommandResult;
import com.emc.storageos.hp3par.command.Position;
import com.emc.storageos.hp3par.command.SystemCommandResult;

import com.emc.storageos.hp3par.command.VirtualLun;
import com.emc.storageos.hp3par.command.VirtualLunsList;

import com.emc.storageos.hp3par.command.VlunResult;

import com.emc.storageos.hp3par.command.VolumeDetailsCommandResult;
import com.emc.storageos.hp3par.connection.HP3PARApiFactory;
import com.emc.storageos.hp3par.utils.HP3PARConstants;
import com.emc.storageos.hp3par.utils.SanUtils;
import com.emc.storageos.storagedriver.AbstractStorageDriver;
import com.emc.storageos.storagedriver.BlockStorageDriver;
import com.emc.storageos.storagedriver.DriverTask;
import com.emc.storageos.storagedriver.HostExportInfo;
import com.emc.storageos.storagedriver.RegistrationData;
import com.emc.storageos.storagedriver.model.Initiator;
import com.emc.storageos.storagedriver.model.StorageHostComponent;
import com.emc.storageos.storagedriver.model.StorageObject;
import com.emc.storageos.storagedriver.model.StorageObject.AccessStatus;
import com.emc.storageos.storagedriver.model.StoragePool;
import com.emc.storageos.storagedriver.model.StoragePool.PoolOperationalStatus;
import com.emc.storageos.storagedriver.model.StoragePool.PoolServiceType;
import com.emc.storageos.storagedriver.model.StoragePool.Protocols;
import com.emc.storageos.storagedriver.model.StoragePool.RaidLevels;
import com.emc.storageos.storagedriver.model.StoragePool.SupportedDriveTypes;
import com.emc.storageos.storagedriver.model.StoragePool.SupportedResourceType;
import com.emc.storageos.storagedriver.model.StoragePort;
import com.emc.storageos.storagedriver.model.StoragePort.TransportType;
import com.emc.storageos.storagedriver.model.StorageProvider;
import com.emc.storageos.storagedriver.model.StorageSystem;
import com.emc.storageos.storagedriver.model.StorageSystem.SupportedProvisioningType;
import com.emc.storageos.storagedriver.model.StorageVolume;
import com.emc.storageos.storagedriver.model.VolumeClone;
import com.emc.storageos.storagedriver.model.VolumeConsistencyGroup;
import com.emc.storageos.storagedriver.model.VolumeMirror;
import com.emc.storageos.storagedriver.model.VolumeSnapshot;
import com.emc.storageos.storagedriver.storagecapabilities.CapabilityInstance;
import com.emc.storageos.storagedriver.storagecapabilities.StorageCapabilities;
import com.emc.storageos.hp3par.command.VolumesCommandResult;
import com.emc.storageos.hp3par.command.VolumeMember;
/**
 * 
 * Implements functions to discover the HP 3PAR storage and provide provisioning
 * You can refer super class for method details
 *
 */
public class HP3PARStorageDriver extends AbstractStorageDriver implements BlockStorageDriver {

	private static final Logger _log = LoggerFactory.getLogger(HP3PARStorageDriver.class);
	private HP3PARApiFactory hp3parApiFactory = null;
	
	// HashMap of list of storage ports discovered for each storage systems.
    // KEY: storage system id would be the key. 
	// VALUE:List of storage ports discovered for the storage system identified by the key.	
    private static Map<String, List<StoragePort>> storagePortMap = new HashMap<String, List<StoragePort>>();
          
	public HP3PARStorageDriver () {
	    _log.info("3PARDriver:HP3PARStorageDriver enter");
	    if (hp3parApiFactory == null) {
	        hp3parApiFactory = new HP3PARApiFactory();
	        hp3parApiFactory.setConnectionTimeoutMs(30000);
	        hp3parApiFactory.setConnManagerTimeout(60000);
	        hp3parApiFactory.setSocketConnectionTimeoutMs(7200000);
	        hp3parApiFactory.init();
	    }
	}
	
	@Override
	public DriverTask getTask(String taskId) {
		_log.info("3PARDriver: getTask Running ");
		// TODO Auto-generated method stub
		return null;
	}

	
	/*
	 * objectid is nothing but the native id of the storage object.
	 * For consistency group it would be the native id of consistency group, which on the 
	 * HP3PAR array is nothing but the name of the volume set. 
	 */
	@Override
	public <T extends StorageObject> T getStorageObject(String storageSystemId, String objectId, Class<T> type) {
		// TODO Auto-generated method stub
		_log.info("3PARDriver: getStorageObject Running ");
		try{
			HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(storageSystemId);		
			ConsistencyGroupResult cgResult = null;
			if (VolumeConsistencyGroup.class.getSimpleName().equals(type.getSimpleName())){
				cgResult = hp3parApi.getVVsetDetails(objectId);
				VolumeConsistencyGroup cg = new VolumeConsistencyGroup();
	            cg.setStorageSystemId(storageSystemId);
	            cg.setNativeId(cgResult.getName());
	            cg.setDeviceLabel(objectId);	            
	            _log.info("3PARDriver: getStorageObject leaving ");
	            return (T)cg;
			}
		}
		catch(Exception e){
			String msg = String.format("3PARDriver: Unable to get Stroage Object for id %s; Error: %s.\n",
					objectId, e.getMessage());
            _log.error(msg);           
            e.printStackTrace();
            return (T)null;
		}
		return null;
	}

	@Override
	public RegistrationData getRegistrationData() {
		_log.info("3PARDriver: getStorageObject Running ");
		// TODO Auto-generated method stub
		return null;
	}
	

	/**
	 * Get storage system information
	 */
	@Override
	public DriverTask discoverStorageSystem(StorageSystem storageSystem) {
	    DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_DISCOVER_STORAGE_SYSTEM);

	        try {
	            _log.info("3PARDriver:discoverStorageSystem information for storage system {}, name {} - start",
	                    storageSystem.getIpAddress(), storageSystem.getSystemName());            

	            URI deviceURI = new URI("https", null, 
                        storageSystem.getIpAddress(), storageSystem.getPortNumber(), "/", null, null);
	            
	            // remove '/' as lock fails with this name
                String uniqueId = deviceURI.toString();
                uniqueId = uniqueId.replace("/", "");

	            HP3PARApi hp3parApi = getHP3PARDevice(storageSystem);
	            String authToken = hp3parApi.getAuthToken(storageSystem.getUsername(),storageSystem.getPassword());
	            if (authToken == null) {
	                throw new HP3PARException("Could not get authentication token");
	            }
	            
	            // Verify user role
	            hp3parApi.verifyUserRole(storageSystem.getUsername());
	            
	            // get storage details
	            SystemCommandResult systemRes = hp3parApi.getSystemDetails();
	            storageSystem.setSerialNumber(systemRes.getSerialNumber());
                storageSystem.setMajorVersion(systemRes.getSystemVersion());
	            storageSystem.setMinorVersion("0"); //as there is no individual portion in 3par api
	            
                // protocols supported
                List<String> protocols = new ArrayList<String>();
                protocols.add(Protocols.iSCSI.toString());
                protocols.add(Protocols.FC.toString());
                protocols.add(Protocols.FCoE.toString());
                storageSystem.setProtocols(protocols);
	            
	            storageSystem.setFirmwareVersion(systemRes.getSystemVersion());
	            storageSystem.setIsSupportedVersion(true); //always supported
	            storageSystem.setModel(systemRes.getModel());
	            storageSystem.setProvisioningType(SupportedProvisioningType.THIN_AND_THICK);
                Set<StorageSystem.SupportedReplication> supportedReplications = new HashSet<>();
                supportedReplications.add(StorageSystem.SupportedReplication.elementReplica);
                supportedReplications.add(StorageSystem.SupportedReplication.groupReplica);
                storageSystem.setSupportedReplications(supportedReplications);
                
                // Storage object properties
                storageSystem.setNativeId(uniqueId + ":" + systemRes.getSerialNumber());

                if (storageSystem.getDeviceLabel() == null) {
	                if (storageSystem.getDisplayName() != null) {
	                    storageSystem.setDeviceLabel(storageSystem.getDisplayName());
	                } else if (systemRes.getName() != null) {
	                    storageSystem.setDeviceLabel(systemRes.getName());
	                    storageSystem.setDisplayName(systemRes.getName());
	                }
	            }

                storageSystem.setAccessStatus(AccessStatus.READ_WRITE);
	            setConnInfoToRegistry(storageSystem.getNativeId(), storageSystem.getIpAddress(), storageSystem.getPortNumber(),
	                    storageSystem.getUsername(), storageSystem.getPassword());

	            task.setStatus(DriverTask.TaskStatus.READY);
	            _log.info("3PARDriver: Successfull discovery storage system {}, name {} - end",
	                        storageSystem.getIpAddress(), storageSystem.getSystemName());    
	        } catch (Exception e) {
	            String msg = String.format("3PARDriver: Unable to discover the storage system %s ip %s; Error: %s.\n",
                        storageSystem.getSystemName(), storageSystem.getIpAddress(), e.getMessage());
	            _log.error(msg);
	            task.setMessage(msg);
	            task.setStatus(DriverTask.TaskStatus.FAILED);
	            e.printStackTrace();
	        }
	    
	    return task;
	}


	/**
	 * Get storage pool information 
	 */
	@Override
	public DriverTask discoverStoragePools(StorageSystem storageSystem, List<StoragePool> storagePools) {
	    //For this 3PAR system
	    _log.info("3PARDriver: discoverStoragePools information for storage system {}, nativeId {} - start",
                storageSystem.getIpAddress(), storageSystem.getNativeId());
	    DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_DISCOVER_STORAGE_POOLS);

	    try {
	        // get Api client
	        HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(storageSystem.getNativeId());

            // get storage pool details
            CPGCommandResult cpgResult = hp3parApi.getAllCPGDetails();
            
            // for each ViPR Storage pool = 3PAR CPG
            for (int index = 0; index < cpgResult.getTotal(); index++) {
                StoragePool pool = new StoragePool();
                CPGMember currMember =  cpgResult.getMembers().get(index);
                
                pool.setPoolName(currMember.getName());
                pool.setStorageSystemId(storageSystem.getNativeId());
                
                Set<Protocols> supportedProtocols = new HashSet<>();
                supportedProtocols.add(Protocols.iSCSI);
                supportedProtocols.add(Protocols.FC);
                supportedProtocols.add(Protocols.FCoE);
                pool.setProtocols(supportedProtocols);
                
                pool.setTotalCapacity((currMember.getUsrUsage().getTotalMiB().longValue() +
                        currMember.getSAUsage().getTotalMiB().longValue() +
                        currMember.getSDUsage().getTotalMiB().longValue()) *
                        HP3PARConstants.KILO_BYTE); 
                pool.setSubscribedCapacity((currMember.getUsrUsage().getUsedMiB().longValue() +
                        currMember.getSAUsage().getUsedMiB().longValue() +
                        currMember.getSDUsage().getUsedMiB().longValue()) *
                        HP3PARConstants.KILO_BYTE);
                pool.setFreeCapacity(pool.getTotalCapacity() - pool.getSubscribedCapacity());
                
                pool.setOperationalStatus(currMember.getState() == 1 ? 
                        PoolOperationalStatus.READY :  PoolOperationalStatus.NOTREADY);
                
                Set<RaidLevels> supportedRaidLevels = new HashSet<>();
                switch (currMember.getSDGrowth().getLDLayout().getRAIDType()) {
                    case 1:
                        supportedRaidLevels.add(RaidLevels.RAID0);
                        break;
                    case 2:
                        supportedRaidLevels.add(RaidLevels.RAID1);
                        break;
                    case 3:
                        supportedRaidLevels.add(RaidLevels.RAID5);
                        break;
                    case 4:
                        supportedRaidLevels.add(RaidLevels.RAID6);
                        break;
                }
                pool.setSupportedRaidLevels(supportedRaidLevels);

                Set<SupportedDriveTypes> supportedDriveTypes = new HashSet<>();
                for (int j = 0; j < currMember.getSDGrowth().getLDLayout().getDiskPatterns().size(); j ++) {
                    switch (currMember.getSDGrowth().getLDLayout().getDiskPatterns().get(j).getDiskType()) {
                        case 1:
                            supportedDriveTypes.add(SupportedDriveTypes.FC);
                            break;
                        case 2:
                            supportedDriveTypes.add(SupportedDriveTypes.NL_SAS);
                            break;
                        case 3:
                            supportedDriveTypes.add(SupportedDriveTypes.SSD);
                            break;
                    }
                }
                pool.setSupportedDriveTypes(supportedDriveTypes);
                
                pool.setMaximumThinVolumeSize(16 * HP3PARConstants.MEGA_BYTE);
                pool.setMinimumThinVolumeSize(256 * HP3PARConstants.KILO_BYTE);
                pool.setMaximumThickVolumeSize(16 * HP3PARConstants.MEGA_BYTE);
                pool.setMinimumThickVolumeSize(256 * HP3PARConstants.KILO_BYTE);

                pool.setSupportedResourceType(SupportedResourceType.THIN_AND_THICK);
                pool.setPoolServiceType(PoolServiceType.block);
                
                // Storage object properties
                pool.setNativeId(currMember.getName()); //SB SDK is not sending pool name in volume creation
                pool.setDeviceLabel(currMember.getName());
                pool.setDisplayName(currMember.getName());
                storageSystem.setAccessStatus(AccessStatus.READ_WRITE);

                _log.info("3PARDriver: added storage pool {}, native id {}",  pool.getPoolName(), pool.getNativeId());
                storagePools.add(pool);
            } //for each storage pool
	        
            task.setStatus(DriverTask.TaskStatus.READY);
            _log.info("3PARDriver: discoverStoragePools information for storage system {}, nativeId {} - end",
                    storageSystem.getIpAddress(), storageSystem.getNativeId());
	    } catch (Exception e) {
	        String msg = String.format
	                ("3PARDriver: Unable to discover the storage pool information for storage system %s native id %s; Error: %s.\n",
                    storageSystem.getSystemName(), storageSystem.getNativeId(), e.getMessage());
            _log.error(msg);
            task.setMessage(msg);
            task.setStatus(DriverTask.TaskStatus.FAILED);
            e.printStackTrace();
        }
        return task;
	}

        @Override
	public DriverTask getStorageVolumes(StorageSystem storageSystem, List<StorageVolume> storageVolumes,
			MutableInt token) {

		if (token.intValue() == 0) {
            //arrayToVolumeToVolumeExportInfoMap.clear();
        }
		
        DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_GET_STORAGE_VOLUMES);

		List<StoragePort> ports = new ArrayList<>();
	    discoverStoragePorts(storageSystem, ports);
	
		try{
			HashMap<String,ArrayList<String>> volumesToVolSetsMap = generateVolumeSetToVolumeMap(storageSystem);
			
	        // get Api client
	        HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(storageSystem.getNativeId());
	        VolumesCommandResult objStorageVolumes = hp3parApi.getStorageVolumes();
	        			                
	        for (int volIndex = 0; volIndex < objStorageVolumes.getTotal() ; volIndex++) {
	        	VolumeMember objVolMember = objStorageVolumes.getMembers().get(volIndex);
	            StorageVolume driverVolume = new StorageVolume();
	            driverVolume.setStorageSystemId(storageSystem.getNativeId());
	            driverVolume.setStoragePoolId(objVolMember.getUserCPG());
	            driverVolume.setNativeId(objVolMember.getName());
	            //if (VOLUMES_IN_CG) {
	            //driverVolume.setConsistencyGroup("driverSimulatorCG-" + token.intValue());
	            //}	            	           
	            driverVolume.setProvisionedCapacity(objVolMember.getSizeMiB() * HP3PARConstants.MEGA_BYTE);
	            driverVolume.setAllocatedCapacity(objVolMember.getSizeMiB() * HP3PARConstants.MEGA_BYTE);
	            driverVolume.setWwn(objVolMember.getWwn());
	            driverVolume.setNativeId(objVolMember.getName()); //required for volume delete
	            driverVolume.setDeviceLabel(objVolMember.getName());
	            
	            //if the volumesToVolSetsMap contains the volume name entry. It means that volume
	            //belongs to consistencygroup(volume set in hp3par teminology)
	            if(volumesToVolSetsMap.containsKey(objVolMember.getName())){
	            	driverVolume.setConsistencyGroup(volumesToVolSetsMap.get(objVolMember.getName()).get(0));
	            }
	            else{
	            	_log.debug("Unmanaged volume volume {}  not part of any consistency group", driverVolume);	
	            }
	            
	            if(objVolMember.isReadOnly()){
	            	driverVolume.setAccessStatus(StorageVolume.AccessStatus.READ_ONLY);
	            }
	            else{
	            	driverVolume.setAccessStatus(StorageVolume.AccessStatus.READ_WRITE);
	            }
	            
	            if(objVolMember.getProvisioningType() == HP3PARConstants.provisioningType.TPVV.getValue() ){
	            	driverVolume.setThinlyProvisioned(true);
	            }
	            else{
	            	driverVolume.setThinlyProvisioned(false);
	            }
	            
	            //TODO: how much should the thin volume preallocation size be.
	            driverVolume.setThinVolumePreAllocationSize(3000L);	            	                                   
	            storageVolumes.add(driverVolume);
	            _log.info("Unmanaged volume info: pool {}, volume {}", driverVolume.getStoragePoolId(), driverVolume);
	            
	        }		
	        task.setStatus(DriverTask.TaskStatus.READY);
		}
		catch(Exception e){
			String msg = String.format
	                ("3PARDriver: Unable to get storagevolumes for storage system %s native id %s; Error: %s.\n",
                    storageSystem.getSystemName(), storageSystem.getNativeId(), e.getMessage());
			task.setMessage(msg);
	        task.setStatus(DriverTask.TaskStatus.FAILED);
	        e.printStackTrace();
		}
		return task;		
	}
        
    /*
     * Returns: Hashmap of volume to volumesets mapping
     * The key of this hashmap will be the name of the volume
     * The value of the hasmap returned will be an array list 
     * of volume sets that the volume belongs to.
     * Example: {volume1: [volumeset5] , volume2:[volumeset1, volumeset2]}
     */
    private HashMap<String,ArrayList<String>> generateVolumeSetToVolumeMap(StorageSystem storageSystem) throws Exception{    
        // get Api client
        HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(storageSystem.getNativeId());
        ConsistencyGroupsListResult objConsisGroupSets = hp3parApi.getVVsetsList();
		HashMap<String,ArrayList<String>> volumeToVolumeSetMap = new HashMap<String,ArrayList<String>>();
		
        _log.info("3PARDriver: objConsisGroupSets.getTotal() information is {}",objConsisGroupSets.getTotal());
        for (Integer index = 0; index < objConsisGroupSets.getTotal(); index++){
        	ConsistencyGroupResult objConsisGroupResult = objConsisGroupSets.getMembers().get(index);
        	
        	if(objConsisGroupResult.getSetmembers()!=null){
	        	for (Integer volIndex = 0 ; volIndex < objConsisGroupResult.getSetmembers().size() ; volIndex++){
	        		String vVolName = objConsisGroupResult.getSetmembers().get(volIndex);
	        		if(!volumeToVolumeSetMap.containsKey(vVolName)){	        			
	        			ArrayList<String> volSetList = new ArrayList<String>();
	        			volSetList.add(objConsisGroupResult.getName());
	            		volumeToVolumeSetMap.put(vVolName, volSetList);
	            	}
	        		else{	        			
	        			volumeToVolumeSetMap.get(vVolName).add(objConsisGroupResult.getName());
	        		}
	        	}        	
        	}
        }
        
        
        _log.info("3PARDriver: volumeToVolumeSetMap information is {}",volumeToVolumeSetMap.toString());
        return volumeToVolumeSetMap;	
    }
        
   

	/**
	 * Get storage port information
	 */
	@Override
	public DriverTask discoverStoragePorts(StorageSystem storageSystem, List<StoragePort> storagePorts) {
        //For this 3PAR system
        _log.info("3PARDriver: discoverStoragePorts information for storage system {}, nativeId {} - start",
                storageSystem.getIpAddress(), storageSystem.getNativeId());
        DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_DISCOVER_STORAGE_PORTS);

        try {
            // get Api client
            HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(storageSystem.getNativeId());

            // get storage port details
            PortCommandResult portResult = hp3parApi.getPortDetails();
            PortStatisticsCommandResult portStatResult = hp3parApi.getPortStatisticsDetail();

            // for each ViPR Storage port = 3PAR host port
            for (Integer index = 0; index < portResult.getTotal(); index++) {
                StoragePort port = new StoragePort();
                PortMembers currMember =  portResult.getMembers().get(index);

                // Consider online target ports 
                if (currMember.getMode() != HP3PARConstants.MODE_TARGET ||
                        currMember.getLinkState() != HP3PARConstants.LINK_READY) {
                    continue;
                }
                
                if (currMember.getLabel() == null) {
                    String label = String.format("port:%s:%s:%s", currMember.getPortPos().getNode(),
                            currMember.getPortPos().getSlot(), currMember.getPortPos().getCardPort());
                    port.setPortName(label);
                } else {
                    port.setPortName(currMember.getLabel());
                    }
                
                port.setStorageSystemId(storageSystem.getNativeId());

                switch(currMember.getProtocol()) {
                    case 1:
                        port.setTransportType(TransportType.FC);
                        break;
                    case 3:
                        port.setTransportType(TransportType.Ethernet);
                        break;
                    case 2:
                        port.setTransportType(TransportType.IP);
                        break;
                    default:
                        _log.warn("3PARDriver: discoverStoragePorts Invalid port {}", port.getPortName());
                        break;
                }
                
                // loop for port speed as specific query is not supported
                for (int stat = 0; stat < portStatResult.getTotal(); stat++) {
                    PortStatMembers currStat = portStatResult.getMembers().get(stat);

                    if (currMember.getPortPos().getNode() == currStat.getNode() && 
                            currMember.getPortPos().getSlot() == currStat.getSlot() && 
                            currMember.getPortPos().getCardPort() == currStat.getCardPort()) {
                        port.setPortSpeed(currStat.getSpeed() * HP3PARConstants.MEGA_BYTE);
                    }
                }

                // grouping with cluster node and slot
                port.setPortGroup(currMember.getPortPos().getNode().toString());
                port.setPortSubGroup(currMember.getPortPos().getSlot().toString());

                // set specific properties based on protocol
                if (port.getTransportType().equals(TransportType.FC.toString()) ||
                        port.getTransportType().equals(TransportType.Ethernet.toString())) {

                    port.setPortNetworkId(SanUtils.formatWWN(currMember.getPortWWN()));
                    // rest of the values
                    port.setEndPointID(port.getPortNetworkId());
                    port.setTcpPortNumber(index.longValue());
                } else {
                    port.setIpAddress(currMember.getIPAddr());
                    port.setPortNetworkId(currMember.getiSCSINmae());
                    port.setTcpPortNumber(currMember.getiSCSIPortInfo().getiSNSPort());
                    // rest of the values                    
                    port.setEndPointID(port.getPortNetworkId());
                }
               
                port.setAvgBandwidth(port.getPortSpeed());
                port.setPortHAZone(String.format("Group-%s", currMember.getPortPos().getNode()));
                
                String id = String.format("%s:%s:%s", currMember.getPortPos().getNode(),
                        currMember.getPortPos().getSlot(), currMember.getPortPos().getCardPort());
                // Storage object properties
                port.setNativeId(id);
                port.setDeviceLabel(port.getPortName());
                port.setDisplayName(port.getPortName());
                storageSystem.setAccessStatus(AccessStatus.READ_WRITE);
                port.setOperationalStatus(StoragePort.OperationalStatus.OK);  
                _log.info("3PARDriver: added storage port {}, native id {}",  port.getPortName(), port.getNativeId());
                storagePorts.add(port);
            } //for each storage pool
                       
            storagePortMap.put(storageSystem.getNativeId() , storagePorts);
            
            task.setStatus(DriverTask.TaskStatus.READY);
            _log.info("3PARDriver: discoverStoragePorts information for storage system {}, nativeId {} - end",
                    storageSystem.getIpAddress(), storageSystem.getNativeId());
        } catch (Exception e) {
            String msg = String.format
                    ("3PARDriver: Unable to discover the storage port information for storage system %s native id %s; Error: %s.\n",
                    storageSystem.getSystemName(), storageSystem.getNativeId(), e.getMessage());
            _log.error(msg);
            task.setMessage(msg);
            task.setStatus(DriverTask.TaskStatus.FAILED);
            e.printStackTrace();
        }
        return task;
    }

	@Override
	public DriverTask discoverStorageHostComponents(StorageSystem storageSystem,
			List<StorageHostComponent> embeddedStorageHostComponents) {
		_log.info("3PARDriver: discoverStorageHostComponents Running ");
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Create requested volumes
	 */
	@Override
	public DriverTask createVolumes(List<StorageVolume> volumes, StorageCapabilities capabilities) {
        DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_CREATE_STORAGE_VOLUMES);

        // For each requested volume (in one or more 3par system)
        for (StorageVolume volume : volumes) {
            try {
                _log.info("3PARDriver:createVolumes for storage system native id {}, volume name {} - start",
                        volume.getStorageSystemId(), volume.getDisplayName());     

                // get Api client
                HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(volume.getStorageSystemId());

                // Create volume
                VolumeDetailsCommandResult volResult = null;
                hp3parApi.createVolume(volume.getDisplayName(), 
                        volume.getStoragePoolId(), 
                        volume.getThinlyProvisioned(), 
                        volume.getRequestedCapacity() / HP3PARConstants.MEGA_BYTE);
                volResult = hp3parApi.getVolumeDetails(volume.getDisplayName());
                
                // Attributes of the volume in array
                volume.setProvisionedCapacity(volResult.getSizeMiB() * HP3PARConstants.MEGA_BYTE);
                volume.setAllocatedCapacity(volResult.getSizeMiB() * HP3PARConstants.MEGA_BYTE);
                volume.setWwn(volResult.getWwn());
                volume.setNativeId(volume.getDisplayName()); //required for volume delete
                volume.setDeviceLabel(volume.getDisplayName());
                volume.setAccessStatus(AccessStatus.READ_WRITE);
                
                //Update Consistency Group
                String volumeCGName = volume.getConsistencyGroup();
                if (volumeCGName != null && !volumeCGName.isEmpty()) {
                	_log.info("3PARDriver:createVolumes Adding volume {} to consistency group {} ",volume.getDisplayName(),volumeCGName);
                	int addMember = 1;
                hp3parApi.updateVVset(volumeCGName,volume.getNativeId(),addMember);
                }

                task.setStatus(DriverTask.TaskStatus.READY);
                _log.info("3PARDriver:createVolumes for storage system native id {}, volume name {} - end",
                        volume.getStorageSystemId(), volume.getDisplayName());            
            } catch (Exception e) {
                String msg = String.format(
                        "3PARDriver: Unable to create volume name %s with pool id %s for storage system native id %s; Error: %s.\n",
                        volume.getDisplayName(), volume.getStoragePoolId(), volume.getStorageSystemId(), e.getMessage());
                _log.error(msg);
                task.setMessage(msg);
                task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
                e.printStackTrace();
            }
        } // end for each volume
        
        return task;
	}

	@Override
	public DriverTask expandVolume(StorageVolume volume, long newCapacity) {
        DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_EXPAND_STORAGE_VOLUMES);
        
        // For this volume
        try {
            _log.info("3PARDriver:expandVolume for storage system native id {}, volume name {} - start",
                    volume.getStorageSystemId(), volume.getDisplayName());     

            // get Api client
            HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(volume.getStorageSystemId());

            // expand volume
            Long additionalSize = newCapacity - volume.getProvisionedCapacity();
            hp3parApi.expandVolume(volume.getDisplayName(), additionalSize / HP3PARConstants.MEGA_BYTE);
            
            volume.setRequestedCapacity(newCapacity);
            
            // actual size of the volume in array
            VolumeDetailsCommandResult volResult = hp3parApi.getVolumeDetails(volume.getDisplayName());
            volume.setProvisionedCapacity(volResult.getSizeMiB() * HP3PARConstants.MEGA_BYTE);
            volume.setAllocatedCapacity(volResult.getSizeMiB() * HP3PARConstants.MEGA_BYTE);

            task.setStatus(DriverTask.TaskStatus.READY);
            _log.info("3PARDriver:expandVolumes for storage system native id {}, volume name {} - end",
                    volume.getStorageSystemId(), volume.getDisplayName());            
        } catch (Exception e) {
            String msg = String.format(
                    "3PARDriver: Unable to expand volume name %s with pool id %s for storage system native id %s; Error: %s.\n",
                    volume.getDisplayName(), volume.getStoragePoolId(), volume.getStorageSystemId(), e.getMessage());
            _log.error(msg);
            task.setMessage(msg);
            task.setStatus(DriverTask.TaskStatus.FAILED);
            e.printStackTrace();
        }
    
    return task;
	}

	@Override
	public DriverTask deleteVolumes(List<StorageVolume> volumes) {
	    DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_DELETE_STORAGE_VOLUMES);

        // For each requested volume (in one or more 3par system)
        for (StorageVolume volume : volumes) {
            try {
                _log.info("3PARDriver:deleteVolumes for storage system native id {}, volume name {} - start",
                        volume.getStorageSystemId(), volume.getDisplayName());     

                // get Api client
                HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(volume.getStorageSystemId());

                
                //Remove from Consistency Group
                String volumeCGName = volume.getConsistencyGroup();
                if (volumeCGName != null && !volumeCGName.isEmpty()) {
                	_log.info("3PARDriver:deleteVolumes Removing volume {} from consistency group {} ",volume.getDisplayName(),volumeCGName);
                	int removeMember = 2;
                hp3parApi.updateVVset(volumeCGName,volume.getNativeId(),removeMember);
                }

                // Delete volume
                hp3parApi.deleteVolume(volume.getNativeId());
                
                task.setStatus(DriverTask.TaskStatus.READY);
                _log.info("3PARDriver:deleteVolumes for storage system native id {}, volume name {} - end",
                        volume.getStorageSystemId(), volume.getDisplayName());            
            } catch (Exception e) {
                String msg = String.format(
                        "3PARDriver: Unable to delete volume name %s with pool id %s for storage system native id %s; Error: %s.\n",
                        volume.getDisplayName(), volume.getStoragePoolId(), volume.getStorageSystemId(), e.getMessage());
                _log.error(msg);
                task.setMessage(msg);
                task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
                e.printStackTrace();
            }
        } // end for each volume
        
        return task;
	}
	
    @Override
    public List<VolumeSnapshot> getVolumeSnapshots(StorageVolume volume) {
    	_log.info("3PARDriver: getVolumeSnapshots Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public List<VolumeClone> getVolumeClones(StorageVolume volume) {
    	_log.info("3PARDriver: getVolumeClones Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public List<VolumeMirror> getVolumeMirrors(StorageVolume volume) {
    	_log.info("3PARDriver: getVolumeMirrors Running ");
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * 
     * Virtual Copy is HP3PAR term for Snapshot. 
     * 
     */
    @Override
    public DriverTask createVolumeSnapshot(List<VolumeSnapshot> snapshots, StorageCapabilities capabilities) {

    	DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_CREATE_SNAPSHOT_VOLUMES);

    	for (VolumeSnapshot snap : snapshots) {
            try {
            	//native id = null , 
                _log.info("3PARDriver: createVolumeSnapshot for storage system native id {}, volume name {} - start",
                		snap.toString(), snap.getDisplayName());  
                Boolean readOnly = true;
                
                // get Api client
                HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(snap.getStorageSystemId());


                VolumeDetailsCommandResult volResult = null;
                if (snap.getAccessStatus() != AccessStatus.READ_ONLY) {
                	readOnly = false;
                }
                // Create volume snapshot
                hp3parApi.createVirtualCopy(snap.getParentId(),snap.getDisplayName(),readOnly);
                volResult = hp3parApi.getVolumeDetails(snap.getDisplayName());
                                
                // Actual size of the volume in array
                //snap.setProvisionedCapacity(volResult.getSizeMiB() * HP3PARConstants.MEGA_BYTE);
                snap.setWwn(volResult.getWwn());
                snap.setNativeId(snap.getDisplayName()); //required for volume delete
                snap.setDeviceLabel(snap.getDisplayName());
                snap.setAccessStatus(snap.getAccessStatus());

                task.setStatus(DriverTask.TaskStatus.READY);
                _log.info("createVolumeSnapshot for storage system native id {}, volume name {} - end",
                		snap.getStorageSystemId(), snap.getDisplayName());            
            } catch (Exception e) {
                String msg = String.format(
                        "3PARDriver: Unable to create volume snap name %s for parent base volume id %s whose storage system native id is %s; Error: %s.\n",
                        snap.getDisplayName(), snap.getParentId(), snap.getStorageSystemId(), e.getMessage());
                _log.error(msg);
                task.setMessage(msg);
                task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
                e.printStackTrace();
            }
        } // end for each volume snapshot creation
        
        return task;
    }

    /**
     * Promote Virtual Copy is HP3PAR term for restore Snapshot.
     * First offline restore then online restore will be tried. 
     */
    @Override
    public DriverTask restoreSnapshot(List<VolumeSnapshot> snapshots) {

	    DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_RESTORE_SNAPSHOT_VOLUMES);

        // Executing restore for each requested volume snapshot (in one or more 3par system)
        for (VolumeSnapshot snap : snapshots) {
            try {
                _log.info("3PARDriver: restoreSnapshot for storage system system id {}, volume name {} , native id {} , all = {} - start",
                		snap.getStorageSystemId(), snap.getDisplayName(), snap.getNativeId(), snap.toString());     

                // get Api client
                HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(snap.getStorageSystemId());

                // restore virtual copy
                hp3parApi.restoreVirtualCopy(snap.getNativeId());
                
                task.setStatus(DriverTask.TaskStatus.READY);
                _log.info("3PARDriver: restoreSnapshot for storage system  id {}, volume snap display name {} - end",
                		snap.getStorageSystemId(), snap.getDisplayName());            
            } catch (Exception e) {
                String msg = String.format(
                        "3PARDriver: Unable to restore volume display name %s with native id %s for storage system id %s; Error: %s.\n",
                        snap.getDisplayName(), snap.getNativeId(), snap.getStorageSystemId(), e.getMessage());
                _log.error(msg);
                task.setMessage(msg);
                task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
                e.printStackTrace();
            }
        } // end for each restore snapshot
        
        return task;
    }

    @Override
    public DriverTask deleteVolumeSnapshot(List<VolumeSnapshot> snapshots) {

	    DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_DELETE_SNAPSHOT_VOLUMES);

        // For each requested volume snapshot (in one or more 3par system)
        for (VolumeSnapshot snap : snapshots) {
            try {
                _log.info("3PARDriver: deleteVolumeSnapshot for storage system native id {}, volume name {} , native id {} - start",
                		snap.getStorageSystemId(), snap.getDisplayName(), snap.getNativeId());     

                // get Api client
                HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(snap.getStorageSystemId());

                // Delete virtual copy
                hp3parApi.deleteVirtualCopy(snap.getNativeId());
                
                task.setStatus(DriverTask.TaskStatus.READY);
                _log.info("3PARDriver: deleteVolumeSnapshot for storage system native id {}, volume name {} - end",
                		snap.getStorageSystemId(), snap.getDisplayName());            
            } catch (Exception e) {
                String msg = String.format(
                        "3PARDriver: Unable to delete volume name %s with native id %s for storage system native id %s; Error: %s.\n",
                        snap.getDisplayName(), snap.getNativeId(), snap.getStorageSystemId(), e.getMessage());
                _log.error(msg);
                task.setMessage(msg);
                task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
                e.printStackTrace();
            }
        } // end for each delete snapshot
        
        return task;
    }

    @Override
    public DriverTask createVolumeClone(List<VolumeClone> clones, StorageCapabilities capabilities) {
    	_log.info("3PARDriver: createVolumeClone Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public DriverTask detachVolumeClone(List<VolumeClone> clones) {
    	_log.info("3PARDriver: detachVolumeClone Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public DriverTask restoreFromClone(List<VolumeClone> clones) {
    	_log.info("3PARDriver: restoreFromClone Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public DriverTask deleteVolumeClone(List<VolumeClone> clones) {
    	_log.info("3PARDriver: deleteVolumeClone Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public DriverTask createVolumeMirror(List<VolumeMirror> mirrors, StorageCapabilities capabilities) {
    	_log.info("3PARDriver: createVolumeMirror Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public DriverTask createConsistencyGroupMirror(VolumeConsistencyGroup consistencyGroup, List<VolumeMirror> mirrors,
            List<CapabilityInstance> capabilities) {
    	_log.info("3PARDriver: createConsistencyGroupMirror Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public DriverTask deleteVolumeMirror(List<VolumeMirror> mirrors) {
    	_log.info("3PARDriver: deleteVolumeMirror Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public DriverTask deleteConsistencyGroupMirror(List<VolumeMirror> mirrors) {
    	_log.info("3PARDriver: deleteConsistencyGroupMirror Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public DriverTask splitVolumeMirror(List<VolumeMirror> mirrors) {
    	_log.info("3PARDriver: splitVolumeMirror Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public DriverTask resumeVolumeMirror(List<VolumeMirror> mirrors) {
    	_log.info("3PARDriver: resumeVolumeMirror Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public DriverTask restoreVolumeMirror(List<VolumeMirror> mirrors) {
    	_log.info("3PARDriver: restoreVolumeMirror Running ");
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * This function should return a HashMap.
     * Key of HashMap : HostName to which the volume is exported
     * Value of the HashMap : HostExportInfo associated with export to HostName
     */
    @Override
    public Map<String, HostExportInfo> getVolumeExportInfoForHosts(StorageVolume volume) {
    	_log.info("3PARDriver: getVolumeExportInfoForHosts Running ");       
    	    	
    	try{    		    		
    		_log.info("volume.getdisplay name is {}",volume.getNativeId());
    		_log.info("volume.getstoragesysid  is {}",volume.getStorageSystemId());    
    		Map<String, HostExportInfo> resultMap = new HashMap<String, HostExportInfo>();
    		    		
    		//get the vlun associated with the volume at consideration.
    		HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(volume.getStorageSystemId());
    		VirtualLunsList vlunsOfVolume = hp3parApi.getVLunsOfVolume(volume.getWwn());
    		    		
    		//process the vlun information by iterating through the vluns
    		//and then for each vlun, we create the appropriate key:value pair
    		//in the resultMap with hostname:HostExportInfo information.    		
    		for( int index=0; index < vlunsOfVolume.getTotal() ; index++ ){    			    		    	
    			_log.info("after virtual lun init");
				VirtualLun objVirtualLun = vlunsOfVolume.getMembers().get(index);
				_log.info("objVirtualLun.getHostName {}", objVirtualLun.getHostname());
				_log.info("objVirtualLun.getPortPos {}", objVirtualLun.getPortPos());
				_log.info("objVirtualLun.getRemoteName {}", objVirtualLun.getRemoteName());
				_log.info("objVirtualLun.getVolumeWWN {}", objVirtualLun.getVolumeWWN());
				_log.info("objVirtualLun.getVolumeName {}", objVirtualLun.getVolumeName());
				_log.info("objVirtualLun.getType {}", objVirtualLun.getType());
								    		
				if(!objVirtualLun.isActive()){
    				continue;				
    			}
    			
    			List<String> volumeIds = new ArrayList<>();
    			List<Initiator> initiators = new ArrayList<Initiator>();
    			List<StoragePort> storageports = new ArrayList<>();
    			
    			//To volumeIds we need to add the native id of volume 
    			//and for hp3par volume name would be the native id
		        volumeIds.add(objVirtualLun.getVolumeName());
	        
		        Initiator hostInitiator = new Initiator();
	        	//hp3par returns remote name in the format like 10000000C98F5C79. 
    	        //we now convert this to the format 10:00:00:00:C9:8F:5C:79
		        String portId = objVirtualLun.getRemoteName().substring(0, 2) + ":" + 
	        				objVirtualLun.getRemoteName().substring(2, 4) + ":" +
	        				objVirtualLun.getRemoteName().substring(4, 6) + ":" +
	        				objVirtualLun.getRemoteName().substring(6, 8) + ":" +
	        				objVirtualLun.getRemoteName().substring(8, 10) + ":" +
	        				objVirtualLun.getRemoteName().substring(10, 12) + ":" +
	        				objVirtualLun.getRemoteName().substring(12, 14) + ":" +
	        				objVirtualLun.getRemoteName().substring(14, 16);
    	        
		        _log.info("before native id");
    		        String nativeId = String.format("%s:%s:%s", objVirtualLun.getPortPos().getNode(),
	    	        		objVirtualLun.getPortPos().getSlot(), objVirtualLun.getPortPos().getCardPort());
	        
	        	//Check which of the storage ports discovered, matches the node:portpos:cardport 
    	        //combination of the VLUN
		        List<StoragePort> storPortsOfStorage = storagePortMap.get(volume.getStorageSystemId());    	        
				_log.info("storPortsOfStorage are {}",storPortsOfStorage);
				_log.info("storPortMap are {}",storagePortMap);

	        	for(int portIndex = 0 ; portIndex < storPortsOfStorage.size() ; portIndex++){
	        		StoragePort port = storPortsOfStorage.get(portIndex);
					_log.info("native id is {}" , nativeId);
					_log.info("port.getNativeId() is {} " , port.getNativeId());

	        		if(port.getNativeId().equals(nativeId)){
	        			storageports.add(port);
	        		}    	        	
	        	}
	        
	        	hostInitiator.setHostName(objVirtualLun.getHostname());    	        
	        	hostInitiator.setPort(portId);
	        	initiators.add(hostInitiator);

    	        HostExportInfo exportInfo = null;
    	        
		        if(resultMap.containsKey(objVirtualLun.getHostname())){
    	        	exportInfo = resultMap.get(objVirtualLun.getHostname());	
					for(int i1 = 0; i1 < storageports.size() ; i1++)
					{
						StoragePort ob1 = storageports.get(i1);
						if(!exportInfo.getTargets().contains(ob1)){
							exportInfo.getTargets().add(ob1);
						}
					}
					for(int i1 = 0; i1 < initiators.size() ; i1++)
					{
						Initiator ob1 = initiators.get(i1);
						if(!exportInfo.getInitiators().contains(ob1)){
							exportInfo.getInitiators().add(ob1);
						}
					}    	       
    		    }
	    	    else{
    	        	exportInfo = new HostExportInfo(objVirtualLun.getHostname(), volumeIds, initiators, storageports);
    	        }
    			    	        
    			resultMap.put(objVirtualLun.getHostname(), exportInfo);
    			_log.info("RESULTMAP FROM GETVOLUMEEXPORTINFO {}",resultMap);
    		}    		
    		return resultMap;
    	}
    	catch (Exception e) {
			String msg = String.format(
					"3PARDriver: Unable to get export info of the volume %s in storage system native id is %s; Error: %s.\n",
					volume.getDisplayName(), volume.getStorageSystemId(), e.getMessage());
			_log.error(msg);			
			e.printStackTrace();
		}
                    	
        return null;
    }
    

    @Override
    public Map<String, HostExportInfo> getSnapshotExportInfoForHosts(VolumeSnapshot snapshot) {
    	_log.info("3PARDriver: getSnapshotExportInfoForHosts Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Map<String, HostExportInfo> getCloneExportInfoForHosts(VolumeClone clone) {
    	_log.info("3PARDriver: getCloneExportInfoForHosts Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Map<String, HostExportInfo> getMirrorExportInfoForHosts(VolumeMirror mirror) {
    	_log.info("3PARDriver: getMirrorExportInfoForHosts Running ");
        // TODO Auto-generated method stub
        return null;
    }

    String get3parHostname(List<Initiator> initiators, String storageId) throws Exception{
        String hp3parHost = null;

        try {
            HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(storageId);
            HostCommandResult hostRes = hp3parApi.getHostDetails();

            Complete:
                // for each host in 3par
                for (int i = 0; i < hostRes.getTotal(); i++) {
                    HostMember hostMemb = hostRes.getMembers().get(i);
                    // for each host initiator sent
                    for (Initiator init:initiators) {

                        // Is initiator FC or iSCSI
                        if (init.getProtocol().toString().compareToIgnoreCase(Protocols.FC.toString()) == 0 || 
                                init.getProtocol().toString().compareToIgnoreCase(Protocols.FCoE.toString()) == 0) {
                            // verify in all FC ports with host 
                            for (int k = 0; k < hostMemb.getFCPaths().size(); k++) {
                                FcPath fcPath = hostMemb.getFCPaths().get(k);
                                if (SanUtils.formatWWN(fcPath.getWwn()).compareToIgnoreCase(init.getPort()) == 0) {
                                    hp3parHost = hostMemb.getName();
                                    break Complete;
                                }
                            }
                        } else {
                            // verify in all iSCSI ports with host 
                            for (int k = 0; k < hostMemb.getiSCSIPaths().size(); k++) {
                                ISCSIPath scsiPath = hostMemb.getiSCSIPaths().get(k);
                                if (scsiPath.getName().compareToIgnoreCase(init.getPort()) == 0) {   
                                    hp3parHost = hostMemb.getName();
                                    break Complete;
                                }
                            }

                        } // if FC or iSCSI
                    }//each initiator
                } // each host
            return hp3parHost;
        } catch (Exception e) {
            _log.error("3PARDriver:get3parHostname could not get 3par registered host name");
            return null;
        }
    }

    /*
     * All volumes in the list will be exported to initato all recommended ports. If a volume can not be exported to 'n' initiators
     * the same will be tried with  
     */
    @Override
    public DriverTask exportVolumesToInitiators(List<Initiator> initiators, List<StorageVolume> volumes, Map<String, String> volumeToHLUMap,
            List<StoragePort> recommendedPorts, List<StoragePort> availablePorts, StorageCapabilities capabilities,
            MutableBoolean usedRecommendedPorts, List<StoragePort> selectedPorts) {
        DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_EXPORT_STORAGE_VOLUMES);
        _log.info("3PARDriver:exportVolumesToInitiators enter");

        // get stand-alone host or cluster. 
        String host = null;
        try {
            if (initiators.get(0).getHostName() != null) {
                // From initiator port wwn/iqn get the hostname registered with 3PAR
                host = get3parHostname(initiators, volumes.get(0).getStorageSystemId());
                if (host == null) {
                    _log.error("3PARDriver:exportVolumesToInitiators error in processing host name");
                    throw new HP3PARException("3PARDriver:exportVolumesToInitiators error in processing host name");
                }
            } else { 
                host = "set:" + initiators.get(0).getClusterName();
            }
        } catch (Exception e) {
            String msg = String.format("3PARDriver: Unable to export, error: %s", e.getMessage());
            _log.error(msg);
            task.setMessage(msg);
            task.setStatus(DriverTask.TaskStatus.FAILED);
            e.printStackTrace();
            return task;
        }

        /* It could be possible that few volumes in a bunch export might get exported and some may not
         * hence volume is kept in outer loop
         */
        Integer totalExport = recommendedPorts.size();
        for (StorageVolume vol:volumes) {
            Integer currExport = 0;
            int hlu = Integer.parseInt(volumeToHLUMap.get(vol.getNativeId()));

            try {
                // volume could belong to different storage system; get specific api client; 
                HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(vol.getStorageSystemId());

                // try with recommended ports
                for (StoragePort port : recommendedPorts) {
                    // verify volume and port belong to same storage
                    if (vol.getStorageSystemId().equalsIgnoreCase(port.getStorageSystemId()) == false) {
                        continue;
                    }

                    _log.info("3PARDriver:exportVolumesToInitiators information for storage system {}, "
                            + "volume {} recommended port {} host {}",
                            port.getStorageSystemId(), vol.getNativeId(), port.getNativeId(), host);            
                    
                    VlunResult vlunRes = hp3parApi.createVlun(vol.getNativeId(), hlu, host, port.getNativeId());
                    if (vlunRes != null && vlunRes.getStatus() == true) {
                        currExport++;
                        usedRecommendedPorts.setValue(true);
                        // update hlu obtained as lun from 3apr & add the port if required
                        volumeToHLUMap.put(vol.getNativeId(), vlunRes.getAssignedLun());
                        if (selectedPorts.contains(port) == false) {
                            selectedPorts.add(port);
                        }
                    }
                } // for recommended ports

                // use available ports for rest of the exports
                for (StoragePort port : availablePorts) {
                    if (currExport == totalExport) {
                        break;
                    }

                    // verify volume and port belongs to same storage
                    if (vol.getStorageSystemId().equalsIgnoreCase(port.getStorageSystemId()) == false) {
                        continue;
                    }

                    _log.info("3PARDriver:exportVolumesToInitiators information for storage system {}, "
                            + "volume {} available port {} host {}",
                            port.getStorageSystemId(), vol.getNativeId(), port.getNativeId(), host);            

                    VlunResult vlunRes = hp3parApi.createVlun(vol.getNativeId(), hlu, host, port.getNativeId());
                    if (vlunRes != null && vlunRes.getStatus() == true) {
                        currExport++;
                        usedRecommendedPorts.setValue(false);
                        // update hlu obtained as lun from 3apr & add the port if required
                        volumeToHLUMap.put(vol.getNativeId(), vlunRes.getAssignedLun());
                        if (selectedPorts.contains(port) == false) {
                            selectedPorts.add(port);
                        }
                    }
                } // for available ports
                
                task.setStatus(DriverTask.TaskStatus.READY);
            } catch (Exception e) {
                String msg = String.format("3PARDriver: Unable to export few volumes, error: %s", e.getMessage());
                _log.error(msg);
                task.setMessage(msg);
                task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
                e.printStackTrace();
            }
        } // for each volume

        _log.info("3PARDriver:exportVolumesToInitiators leave");
        return task;
    }

    /*
     * Single initiator might have multiple volumes and single volume could be exported to multiple initiators
     * All volumes will be tried for unexport from all initiators
     *  
     */
    @Override
    public DriverTask unexportVolumesFromInitiators(List<Initiator> initiators, List<StorageVolume> volumes) {
    	_log.info("3PARDriver: unexportVolumesFromInitiators Running ");
        // TODO Auto-generated method stub
        return null;
    }

    @Override
	public DriverTask createConsistencyGroup(VolumeConsistencyGroup consistencyGroup) {

		DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_CREATE_CONSISTENCY_GROUP);

		try {
			_log.info("3PARDriver: createConsistencyGroup for storage system  id {}, display name {} , native id {}, device lable id {} , cosistency group id {}  - start",
					consistencyGroup.getStorageSystemId(), consistencyGroup.getDisplayName(),
					consistencyGroup.getNativeId(),consistencyGroup.getDeviceLabel(),consistencyGroup.getConsistencyGroup());

			// get Api client
			HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(consistencyGroup.getStorageSystemId());

			ConsistencyGroupResult cgResult = null;

			// Create VV Set / Consistency Group
			hp3parApi.createVVset(consistencyGroup.getDisplayName());
			cgResult = hp3parApi.getVVsetDetails(consistencyGroup.getDisplayName());

			_log.info("3PARDriver: createConsistencyGroup getDetails "+cgResult.getDetails());
			consistencyGroup.setNativeId(consistencyGroup.getDisplayName());
			consistencyGroup.setDeviceLabel(consistencyGroup.getDisplayName());

			task.setStatus(DriverTask.TaskStatus.READY);
			_log.info("3PARDriver: createConsistencyGroup for storage system  id {}, display name {} , native id {}, device lable id {} , cosistency group id {}  - end",
					consistencyGroup.getStorageSystemId(), consistencyGroup.getDisplayName(),
					consistencyGroup.getNativeId(),consistencyGroup.getDeviceLabel(),consistencyGroup.getConsistencyGroup());
		} catch (Exception e) {
			String msg = String.format(
					"3PARDriver: Unable to create consistency group name %s in storage system native id is %s; Error: %s.\n",
					consistencyGroup.getDisplayName(), consistencyGroup.getStorageSystemId(), e.getMessage());
			_log.error(msg);
			task.setMessage(msg);
			task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
			e.printStackTrace();
		}
        
        return task;
        
    }

    /**
     * Delete VV Set or consistency group
     * 
     */
    @Override
    public DriverTask deleteConsistencyGroup(VolumeConsistencyGroup consistencyGroup) {
		_log.info("3PARDriver: deleteConsistencyGroup for storage system  id {}, display name {} , native id {}, device lable id {} , cosistency group id {}  - start",
				consistencyGroup.getStorageSystemId(), consistencyGroup.getDisplayName(),
				consistencyGroup.getNativeId(),consistencyGroup.getDeviceLabel(),consistencyGroup.getConsistencyGroup());

	    DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_DELETE_CONSISTENCY_GROUP);

            try {

                // get Api client
                HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(consistencyGroup.getStorageSystemId());

                // Delete virtual copies of CG
                hp3parApi.deleteVVset(consistencyGroup.getNativeId());
                
                task.setStatus(DriverTask.TaskStatus.READY);
                _log.info("3PARDriver: deleteConsistencyGroup for storage system native id {}, volume name {} - end",
                		consistencyGroup.getStorageSystemId(), consistencyGroup.getDisplayName());            
            } catch (Exception e) {
                String msg = String.format(
                        "3PARDriver: deleteConsistencyGroup Unable to delete CG %s with native id %s which is part of storage system native id %s; Error: %s.\n",
                        consistencyGroup.getDisplayName(), consistencyGroup.getNativeId(), consistencyGroup.getStorageSystemId(), e.getMessage());
                _log.error(msg);
                task.setMessage(msg);
                task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
                e.printStackTrace();
            }
        
        return task;
    
    }

    @Override
    public DriverTask createConsistencyGroupSnapshot(VolumeConsistencyGroup consistencyGroup, List<VolumeSnapshot> snapshots,
			List<CapabilityInstance> capabilities) {
		_log.info(
				"3PARDriver: createConsistencyGroupSnapshot for storage system  id {}, display name {} , native id {} - start",
				consistencyGroup.getStorageSystemId(), consistencyGroup.getDisplayName(),
				consistencyGroup.getNativeId());
		String  VVsetSnapshotName = consistencyGroup.getDisplayName();
		
		DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_SNAPSHOT_CONSISTENCY_GROUP);
		VolumeDetailsCommandResult volResult = null;
		
		try {

			Boolean readOnly = true;

			// get Vipr generated Snapshot name
		   	for (VolumeSnapshot snap : snapshots) {
	            
	            	//native id = null , 
	                _log.info("3PARDriver: createConsistencyGroupSnapshot for volume native id {}, snap shot name generated is {} - start",
	                		snap.getParentId(), snap.getDisplayName());  
	            
	                if (snap.getAccessStatus() != AccessStatus.READ_ONLY) {
	                	readOnly = false;
	                }
	                
	                String generatedSnapshotName = snap.getDisplayName();
	                VVsetSnapshotName = generatedSnapshotName.substring(0, generatedSnapshotName.lastIndexOf("-")) + "-" ;
	                _log.info("3PARDriver: createConsistencyGroupSnapshot VVsetSnapshotName {} ",VVsetSnapshotName);
	                break;

		   	}
		   	
		   	
			// get Api client
			HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(consistencyGroup.getStorageSystemId());
		   	
			// Create vvset snapshot
			hp3parApi.createVVsetVirtualCopy(consistencyGroup.getNativeId(), VVsetSnapshotName,readOnly);
			int volumeNumber = 0;
			int snapVolumeCount  = snapshots.size();
			
			/**
			 * for each volume snapshot available 
			 * find correct snapshot object and set the values
			 */
						
			while (volumeNumber < snapVolumeCount) {

				String snapshotCreated = VVsetSnapshotName + volumeNumber;
				_log.info(
						"3PARDriver: createConsistencyGroupSnapshot snapshotCreated {}, volumeNumber {} , snapVolumeCount {} - start",
						snapshotCreated, volumeNumber, snapVolumeCount);

				volResult = hp3parApi.getVolumeDetails(VVsetSnapshotName + volumeNumber);
				if (volResult != null) {
					String baseVolume = volResult.getCopyOf();

					if (baseVolume != null) {
						for (VolumeSnapshot snap : snapshots) {
							_log.info(
									"createConsistencyGroupSnapshot Snapshot system native id {}, Parent Volume {}, access status {}, display name {}, native Name {}, DeviceLabel {} - Before",
									snap.getStorageSystemId(), snap.getParentId(), snap.getAccessStatus(),
									snap.getDisplayName(), snap.getNativeId(), snap.getDeviceLabel());

							String parentName = snap.getParentId();
							_log.info("createConsistencyGroupSnapshot +++{}++{}+++ ", parentName , baseVolume);
							if (parentName.equals(baseVolume)) {
								_log.info(
										"createConsistencyGroupSnapshot snap name {} wwn {} deviceLable {} displayname {} " 
										, snap.getNativeId(),snap.getWwn(),snap.getDeviceLabel(),snap.getDisplayName());
								snap.setWwn(volResult.getWwn());
								snap.setNativeId(volResult.getName());
								snap.setDeviceLabel(volResult.getName());
								snap.setLabel(volResult.getName());
								// snap.setAccessStatus(volResult.getAccessStatus());
								snap.setDisplayName(volResult.getName());
								
								_log.info(
										"createConsistencyGroupSnapshot volResult name {} wwn {} " , volResult.getName(),volResult.getWwn());
							}

							_log.info(
									"createConsistencyGroupSnapshot Snapshot system native id {}, Parent Volume {}, access status {}, display name {}, native Name {}, DeviceLabel {} - After",
									snap.getStorageSystemId(), snap.getParentId(), snap.getAccessStatus(),
									snap.getDisplayName(), snap.getNativeId(), snap.getDeviceLabel());

						}
					} else {
						_log.info("3PARDriver: createConsistencyGroupSnapshot baseVolume is null");

					}

				} else {
					_log.info("3PARDriver: createConsistencyGroupSnapshot volResult is null");

				}
				volumeNumber = volumeNumber + 1;
			}
			
			task.setStatus(DriverTask.TaskStatus.READY);
			_log.info(
					"createConsistencyGroupSnapshot for storage system native id {}, CG display Name {}, CG native id {} - end",
					consistencyGroup.getStorageSystemId(), consistencyGroup.getDisplayName(),
					consistencyGroup.getNativeId());
		} catch (Exception e) {
			String msg = String.format(
					"3PARDriver: Unable to create vv set snap name %s and its native id %s whose storage system  id is %s; Error: %s.\n",
					VVsetSnapshotName, consistencyGroup.getNativeId(), consistencyGroup.getStorageSystemId(), e.getMessage());
			_log.error(msg);
			task.setMessage(msg);
			task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
			e.printStackTrace();
		}
        
        return task;
        
    }

    @Override
    public DriverTask deleteConsistencyGroupSnapshot(List<VolumeSnapshot> snapshots) {

	    DriverTask task = createDriverTask(HP3PARConstants.TASK_TYPE_DELETE_SNAPSHOT_CONSISTENCY_GROUP);

        // For each requested CG volume snapshot 
        for (VolumeSnapshot snap : snapshots) {
            try {
                _log.info("3PARDriver: deleteConsistencyGroupSnapshot for storage system native id {}, volume name {} , native id {} - start",
                		snap.getStorageSystemId(), snap.getDisplayName(), snap.getNativeId());     

                // get Api client
                HP3PARApi hp3parApi = getHP3PARDeviceFromNativeId(snap.getStorageSystemId());

                // Delete virtual copy
                hp3parApi.deleteVirtualCopy(snap.getNativeId());
                
                task.setStatus(DriverTask.TaskStatus.READY);
                _log.info("3PARDriver: deleteConsistencyGroupSnapshot for storage system native id {}, volume name {} - end",
                		snap.getStorageSystemId(), snap.getDisplayName());            
            } catch (Exception e) {
                String msg = String.format(
                        "3PARDriver: deleteConsistencyGroupSnapshot Unable to delete cg snapshot name %s with native id %s for storage system native id %s; Error: %s.\n",
                        snap.getDisplayName(), snap.getNativeId(), snap.getStorageSystemId(), e.getMessage());
                _log.error(msg);
                task.setMessage(msg);
                task.setStatus(DriverTask.TaskStatus.PARTIALLY_FAILED);
                e.printStackTrace();
            }
        } // end for each delete snapshot
        
        return task;
    }

    @Override
    public DriverTask createConsistencyGroupClone(VolumeConsistencyGroup consistencyGroup, List<VolumeClone> clones,
            List<CapabilityInstance> capabilities) {
    	_log.info("3PARDriver: createConsistencyGroupClone for storage system  id {}, display name {} , native id {} - start",
    			consistencyGroup.getStorageSystemId(), consistencyGroup.getDisplayName(), consistencyGroup.getNativeId());

        // TODO Auto-generated method stub
        return null;
    }
    
    /*
     * Internal methods in the driver
     */
    private HP3PARApi getHP3PARDevice(StorageSystem hp3parSystem) throws HP3PARException {
        URI deviceURI;
        _log.info("3PARDriver:getHP3PARDevice input storage system");
        
        try {
            deviceURI = new URI("https", null, hp3parSystem.getIpAddress(), hp3parSystem.getPortNumber(), "/", null, null);
            return hp3parApiFactory
                    .getRESTClient(deviceURI, hp3parSystem.getUsername(), hp3parSystem.getPassword());
        } catch (Exception e) {
            e.printStackTrace();
            _log.error("3PARDriver:Error in getting 3PAR device, with StorageSystem");
            throw new HP3PARException("Error in getting 3PAR device");
        }       
    }

    private HP3PARApi getHP3PARDevice(String ip, String port, String user, String pass) throws HP3PARException {
        URI deviceURI;
        _log.info("3PARDriver:getHP3PARDevice input full details");
        
        try {
            deviceURI = new URI("https", null, ip, Integer.parseInt(port), "/", null, null);
            return hp3parApiFactory
                    .getRESTClient(deviceURI, user, pass);
        } catch (Exception e) {
            e.printStackTrace();
            _log.error("3PARDriver:Error in getting 3PAR device with details");
            throw new HP3PARException("Error in getting 3PAR device");
        }       
    }
    
    private HP3PARApi getHP3PARDeviceFromNativeId(String nativeId) throws HP3PARException {
        try {
            Map<String, List<String>> connectionInfo =
                    driverRegistry.getDriverAttributesForKey(HP3PARConstants.DRIVER_NAME, nativeId);
            List<String> ipAddress = connectionInfo.get(HP3PARConstants.IP_ADDRESS);
            List<String> portNumber = connectionInfo.get(HP3PARConstants.PORT_NUMBER);
            List<String> userName = connectionInfo.get(HP3PARConstants.USER_NAME);
            List<String> password = connectionInfo.get(HP3PARConstants.PASSWORD);
            HP3PARApi hp3parApi = getHP3PARDevice(ipAddress.get(0), portNumber.get(0),
                    userName.get(0),password.get(0));
            return hp3parApi;
        } catch (Exception e) {
            e.printStackTrace();
            _log.error("3PARDriver:Error in getting 3PAR device with nativeId");
            throw new HP3PARException("Error in getting 3PAR device");
        }
    }

    /**
     * Create driver task for task type
     *
     * @param taskType
     */
    private DriverTask createDriverTask(String taskType) {
        String taskID = String.format("%s+%s+%s", HP3PARConstants.DRIVER_NAME, taskType, UUID.randomUUID());
        DriverTask task = new HP3PARDriverTask(taskID);
        return task;
    }
    
    private void setConnInfoToRegistry(String systemNativeId, String ipAddress, int port, String username, String password) {
        _log.info("3PARDriver:Saving connection info in registry enter");
        Map<String, List<String>> attributes = new HashMap<>();
        List<String> listIP = new ArrayList<>();
        List<String> listPort = new ArrayList<>();
        List<String> listUserName = new ArrayList<>();
        List<String> listPwd = new ArrayList<>();

        listIP.add(ipAddress);
        attributes.put(HP3PARConstants.IP_ADDRESS, listIP);
                listPort.add(Integer.toString(port));
        attributes.put(HP3PARConstants.PORT_NUMBER, listPort);
                listUserName.add(username);
        attributes.put(HP3PARConstants.USER_NAME, listUserName);
                listPwd.add(password);
        attributes.put(HP3PARConstants.PASSWORD, listPwd);
        this.driverRegistry.setDriverAttributesForKey(HP3PARConstants.DRIVER_NAME, systemNativeId, attributes);
        _log.info("3PARDriver:Saving connection info in registry leave");
    }

    @Override
    public DriverTask discoverStorageProvider(StorageProvider storageProvider, List<StorageSystem> storageSystems) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean validateStorageProviderConnection(StorageProvider storageProvider) {
        // TODO Auto-generated method stub
        return false;
    }
}
