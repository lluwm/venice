package com.linkedin.venice.controller;

import com.linkedin.venice.helix.HelixReadWriteSchemaRepository;
import com.linkedin.venice.helix.Replica;
import com.linkedin.venice.helix.ZkWhitelistAccessor;
import com.linkedin.venice.kafka.TopicManager;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.exceptions.VeniceNoStoreException;
import com.linkedin.venice.helix.HelixReadWriteStoreRepository;
import com.linkedin.venice.helix.HelixState;
import com.linkedin.venice.job.ExecutionStatus;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.Partition;
import com.linkedin.venice.meta.PartitionAssignment;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.schema.SchemaEntry;
import com.linkedin.venice.utils.HelixUtils;
import com.linkedin.venice.utils.PartitionCountUtils;
import com.linkedin.venice.utils.Utils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.Set;
import org.apache.commons.io.IOUtils;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixManager;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.InstanceType;
import org.apache.helix.PropertyKey;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.manager.zk.ZKHelixManager;
import org.apache.helix.manager.zk.ZkClient;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.HelixConfigScope;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.LeaderStandbySMD;
import org.apache.helix.model.LiveInstance;
import org.apache.helix.model.builder.HelixConfigScopeBuilder;
import org.apache.helix.participant.StateMachineEngine;
import org.apache.log4j.Logger;


/**
 * Helix Admin based on 0.6.5 APIs.
 *
 * <p>
 * After using controller as service mode. There are two levels of cluster and controllers. Each venice controller will
 * hold a parent helix controller, which will always connecting to ZK. And there is a cluster only used for all of these
 * parent controllers. The second level is our venice clusters. Like prod cluster, dev cluster etc. Each of cluster will
 * be Helix resource in the controller's cluster. So that Helix will choose one of parent controller becoming the leader
 * of our venice cluster. In the state transition handler, we will create sub-controller for this venice cluster only.
 */
public class VeniceHelixAdmin implements Admin {
    private final String controllerClusterName;
    private final int controllerClusterReplica;
    private final String controllerName;
    private final String kafkaBootstrapServers;


    public static final int CONTROLLER_CLUSTER_NUMBER_OF_PARTITION = 1;
    private static final Logger logger = Logger.getLogger(VeniceHelixAdmin.class);
    private final HelixAdmin admin;
    private TopicManager topicManager;
    private final ZkClient zkClient;
    private ZkWhitelistAccessor whitelistAccessor;
    /**
     * Parent controller, it always being connected to Helix. And will create sub-controller for specific cluster when
     * getting notification from Helix.
     */
    private HelixManager manager;

    private VeniceDistClusterControllerStateModelFactory controllerStateModelFactory;
    //TODO Use different configs for different clusters when creating helix admin.
    public VeniceHelixAdmin(VeniceControllerConfig config) {
        this.controllerName = Utils.getHelixNodeIdentifier(config.getAdminPort());
        this.controllerClusterName = config.getControllerClusterName();
        this.controllerClusterReplica = config.getControllerClusterReplica();
        this.kafkaBootstrapServers =  config.getKafkaBootstrapServers();

        // TODO: Re-use the internal zkClient for the ZKHelixAdmin and TopicManager.
        this.admin = new ZKHelixAdmin(config.getZkAddress());
        //There is no way to get the internal zkClient from HelixManager or HelixAdmin. So create a new one here.
        this.zkClient = new ZkClient(config.getZkAddress(), ZkClient.DEFAULT_SESSION_TIMEOUT, ZkClient.DEFAULT_CONNECTION_TIMEOUT);
        this.topicManager = new TopicManager(config.getKafkaZkAddress());
        this.whitelistAccessor = new ZkWhitelistAccessor(zkClient);

        // Create the parent controller and related cluster if required.
        createControllerClusterIfRequired();
        controllerStateModelFactory =
            new VeniceDistClusterControllerStateModelFactory(zkClient);
        controllerStateModelFactory.addClusterConfig(config.getClusterName(), config);
        // TODO should the Manager initialization be part of start method instead of the constructor.
        manager = HelixManagerFactory
            .getZKHelixManager(controllerClusterName, controllerName, InstanceType.CONTROLLER_PARTICIPANT, config.getControllerClusterZkAddresss());
        StateMachineEngine stateMachine = manager.getStateMachineEngine();
        stateMachine.registerStateModelFactory(LeaderStandbySMD.name, controllerStateModelFactory);
        try {
            manager.connect();
        } catch (Exception ex) {
            String errorMessage = " Error starting Helix controller cluster " +
                    controllerClusterName + " controller " + controllerName;
            logger.error(errorMessage, ex);
            throw new VeniceException(errorMessage, ex);
        }
    }

    private void setJobManagerAdmin(VeniceJobManager jobManager) {
        // TODO : Alternative ways of doing admin operation need to be explored. In the current code Admin has
        // relationship to all the resources and stores, but resources and stores are not aware of the admin.
        // This creates one centralized point, where all the admin operations need to flow through.
        // IF Admin object is shared, but stores/resources implement their own operation, it might be easier
        // to maintain and understand the code.

        jobManager.setAdmin(this);
    }

    @Override
    public synchronized void start(String clusterName) {
        //Simply validate cluster name here.
        clusterName = clusterName.trim();
        if (clusterName.startsWith("/") || clusterName.endsWith("/") || clusterName.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("Invalid cluster name:" + clusterName);
        }
        createClusterIfRequired(clusterName);
        // The resource and partition may be disabled for this controller before, we need to enable again at first. Then the state transition will be triggered.
        List<String> partitionNames = new ArrayList<>();
        partitionNames.add(VeniceDistClusterControllerStateModel.getPartitionNameFromVeniceClusterName(clusterName));
        admin.enablePartition(true, controllerClusterName, controllerName, clusterName, partitionNames);
        try {
            controllerStateModelFactory.waitUntilClusterStarted(clusterName);
            if(controllerStateModelFactory.getModel(clusterName).getCurrentState().equals(HelixState.ERROR_STATE)){
                String errorMsg = "Controller for " + clusterName + " is not started, because we met error when doing Helix state transition.";
                throw new VeniceException(errorMsg);
            }
        } catch (InterruptedException e) {
            String errorMsg = "Controller for " + clusterName + " is not started";
            logger.error(errorMsg, e);
            throw new VeniceException(errorMsg, e);
        }

        logger.info("VeniceHelixAdmin is started. Controller name: '" + controllerName +
            "', Cluster name: '" + clusterName + "'.");
    }

    @Override
    public synchronized void addStore(String clusterName, String storeName, String owner) {
        checkControllerMastership(clusterName);
        HelixReadWriteStoreRepository repository = getVeniceHelixResource(clusterName).getMetadataRepository();
        if (repository.getStore(storeName) != null) {
            throwStoreAlreadyExists(clusterName, storeName);
        }
        VeniceControllerClusterConfig config = getVeniceHelixResource(clusterName).getConfig();
        Store store = new Store(storeName, owner, System.currentTimeMillis(), config.getPersistenceType(),
            config.getRoutingStrategy(), config.getReadStrategy(), config.getOfflinePushStrategy());
        repository.addStore(store);
    }

    /**
     * Throws VeniceException if the version is unavailable for reservation
     *
     * @param clusterName
     * @param storeName
     * @param versionNumberToReserve
     */
    @Override
    public synchronized void reserveVersion(String clusterName, String storeName, int versionNumberToReserve){
        checkControllerMastership(clusterName);
        boolean success = false;
        HelixReadWriteStoreRepository repository = getVeniceHelixResource(clusterName).getMetadataRepository();
        repository.lock();
        try {
            Store store = repository.getStore(storeName);
            if (store == null) {
                throwStoreDoesNotExist(clusterName, storeName);
            }
            store.reserveVersionNumber(versionNumberToReserve); /* throws VeniceException on failure */
            repository.updateStore(store);
            logger.info("Successfully reserved version " + versionNumberToReserve + " for store " + storeName);
        } finally {
            repository.unLock();
        }
    }

    private final static int VERSION_ID_UNSET = -1;

    @Override
    public synchronized Version addVersion(String clusterName, String storeName,int versionNumber, int numberOfPartition, int replicaFactor) {
        checkControllerMastership(clusterName);
        HelixReadWriteStoreRepository repository = getVeniceHelixResource(clusterName).getMetadataRepository();

        Version version = null;
        repository.lock();
        try {
            Store store = repository.getStore(storeName);
            if(store == null){
                throwStoreDoesNotExist(clusterName, storeName);
            }

            if(versionNumber == VERSION_ID_UNSET) {
                // No Version supplied, generate new version.
                version = store.increaseVersion();
            } else {
                if (store.containsVersion(versionNumber)) {
                    throwVersionAlreadyExists(storeName, versionNumber);
                }
                //TODO add constraints that added version should be smaller than reserved one.
                version = new Version(storeName, versionNumber);
                store.addVersion(version);
            }
            // Update default partition count if it have not been assigned.
            if(store.getPartitionCount() == 0){
                store.setPartitionCount(numberOfPartition);
            }
            repository.updateStore(store);
            logger.info("Add version:"+version.getNumber()+" for store:" + storeName);
        } finally {
            repository.unLock();
        }

        VeniceControllerClusterConfig clusterConfig = controllerStateModelFactory.getModel(clusterName).getResources().getConfig();
        createKafkaTopic(clusterName, version.kafkaTopicName(), numberOfPartition, clusterConfig.getKafkaReplicaFactor());
        createHelixResources(clusterName, version.kafkaTopicName(), numberOfPartition, replicaFactor);
        //Start offline push job for this new version.
        startOfflinePush(clusterName, version.kafkaTopicName(), numberOfPartition, replicaFactor);
        return version;
    }

    @Override
    public synchronized Version incrementVersion(String clusterName, String storeName, int numberOfPartition,
        int replicaFactor) {
        return addVersion(clusterName , storeName , VERSION_ID_UNSET , numberOfPartition , replicaFactor);
    }

    @Override
    public int getCurrentVersion(String clusterName, String storeName){
        Store store = getStoreForReadOnly(clusterName, storeName);
        int version = store.getCurrentVersion(); /* Does not modify the store */
        return version;
    }

    @Override
    public Version peekNextVersion(String clusterName, String storeName) {
        Store store = getStoreForReadOnly(clusterName, storeName);
        Version version = store.peekNextVersion(); /* Does not modify the store */
        logger.info("Next version would be: " + version.getNumber() + " for store: " + storeName);
        return version;
    }

    /***
     * If you need to do mutations on the store, then you must hold onto the lock until you've persisted your mutations.
     * Only use this method if you're doing read-only operations on the store.
     * @param clusterName
     * @param storeName
     * @return
     */
    private Store getStoreForReadOnly(String clusterName, String storeName){
        checkControllerMastership(clusterName);
        HelixReadWriteStoreRepository repository = getVeniceHelixResource(clusterName).getMetadataRepository();
        repository.lock();
        try {
            Store store = repository.getStore(storeName);
            if(store == null){
                throw new VeniceNoStoreException(storeName);
            }
            return store; /* is a clone */
        } finally {
            repository.unLock();
        }
    }

    @Override
    public List<Version> versionsForStore(String clusterName, String storeName){
        checkControllerMastership(clusterName);
        HelixReadWriteStoreRepository repository = getVeniceHelixResource(clusterName).getMetadataRepository();
        List<Version> versions;
        repository.lock();
        try {
            Store store = repository.getStore(storeName);
            if(store == null){
                throw new VeniceNoStoreException(storeName);
            }
            versions = store.getVersions();
        } finally {
            repository.unLock();
        }
        return versions;
    }

    @Override
    public List<Store> getAllStores(String clusterName){
        checkControllerMastership(clusterName);
        HelixReadWriteStoreRepository repository = getVeniceHelixResource(clusterName).getMetadataRepository();
        repository.lock();
        try {
            return repository.listStores();
        } finally {
            repository.unLock();
        }
    }

    @Override
    public Store getStore(String clusterName, String storeName){
        checkControllerMastership(clusterName);
        HelixReadWriteStoreRepository repository = getVeniceHelixResource(clusterName).getMetadataRepository();
        repository.lock();
        try {
            return repository.getStore(storeName);
        } finally {
            repository.unLock();
        }
    }

    @Override
    public synchronized void setCurrentVersion(String clusterName, String storeName, int versionNumber){
        checkControllerMastership(clusterName);
        HelixReadWriteStoreRepository repository = getVeniceHelixResource(clusterName).getMetadataRepository();
        repository.lock();
        try {
            Store store = repository.getStore(storeName);
            if(store.containsVersion(versionNumber)) {
                store.setCurrentVersion(versionNumber);
            } else {
                String errorMsg = "Version:" + versionNumber + " does not exist for store:" + storeName;
                logger.error(errorMsg);
                throw new VeniceException(errorMsg);
            }
            repository.updateStore(store);
            logger.info("Set version:" + versionNumber +" for store:" + storeName);
        } finally {
            repository.unLock();
        }
    }

    // TODO: Though controller can control, multiple Venice-clusters, kafka topic name needs to be unique
    // among them. If there the same store name is present in two different venice clusters, the code
    // will fail and might exhibit other issues.
    private void createKafkaTopic(String clusterName, String kafkaTopic, int numberOfPartition, int kafkaReplicaFactor) {
        checkControllerMastership(clusterName);
        topicManager.createTopic(kafkaTopic, numberOfPartition, kafkaReplicaFactor);
    }

    private void createHelixResources(String clusterName, String kafkaTopic , int numberOfPartition , int replicaFactor) {
        if (!admin.getResourcesInCluster(clusterName).contains(kafkaTopic)) {
            admin.addResource(clusterName, kafkaTopic, numberOfPartition,
                    VeniceStateModel.PARTITION_ONLINE_OFFLINE_STATE_MODEL, IdealState.RebalanceMode.FULL_AUTO.toString());
            admin.rebalance(clusterName, kafkaTopic, replicaFactor);
            logger.info("Added " + kafkaTopic + " as a resource to cluster: " + clusterName);
        } else {
            throwResourceAlreadyExists(kafkaTopic);
        }

    }

    @Override
    public void startOfflinePush(String clusterName, String kafkaTopic, int numberOfPartition, int replicaFactor) {
        checkControllerMastership(clusterName);
        VeniceJobManager jobManager = controllerStateModelFactory.getModel(clusterName).getResources().getJobManager();
        setJobManagerAdmin(jobManager);
        jobManager.startOfflineJob(kafkaTopic, numberOfPartition, replicaFactor);
    }

    @Override
    public void deleteOldStoreVersion(String clusterName, String kafkaTopic) {
        checkControllerMastership(clusterName);
        admin.dropResource(clusterName, kafkaTopic);
        logger.info("Successfully dropped the resource " + kafkaTopic + " for cluster " + clusterName);
    }

    @Override
    public SchemaEntry getKeySchema(String clusterName, String storeName) {
        checkControllerMastership(clusterName);
        HelixReadWriteSchemaRepository schemaRepo = getVeniceHelixResource(clusterName).getSchemaRepository();
        return schemaRepo.getKeySchema(storeName);
    }

    @Override
    public SchemaEntry initKeySchema(String clusterName, String storeName, String keySchemaStr) {
        checkControllerMastership(clusterName);
        HelixReadWriteSchemaRepository schemaRepo = getVeniceHelixResource(clusterName).getSchemaRepository();
        return schemaRepo.initKeySchema(storeName, keySchemaStr);
    }

    @Override
    public Collection<SchemaEntry> getValueSchemas(String clusterName, String storeName) {
        checkControllerMastership(clusterName);
        HelixReadWriteSchemaRepository schemaRepo = getVeniceHelixResource(clusterName).getSchemaRepository();
        return schemaRepo.getValueSchemas(storeName);
    }

    @Override
    public int getValueSchemaId(String clusterName, String storeName, String valueSchemaStr) {
        checkControllerMastership(clusterName);
        HelixReadWriteSchemaRepository schemaRepo = getVeniceHelixResource(clusterName).getSchemaRepository();
        return schemaRepo.getValueSchemaId(storeName, valueSchemaStr);
    }

    @Override
    public SchemaEntry getValueSchema(String clusterName, String storeName, int id) {
        checkControllerMastership(clusterName);
        HelixReadWriteSchemaRepository schemaRepo = getVeniceHelixResource(clusterName).getSchemaRepository();
        return schemaRepo.getValueSchema(storeName, id);
    }

    @Override
    public SchemaEntry addValueSchema(String clusterName, String storeName, String valueSchemaStr) {
        checkControllerMastership(clusterName);
        HelixReadWriteSchemaRepository schemaRepo = getVeniceHelixResource(clusterName).getSchemaRepository();
        return schemaRepo.addValueSchema(storeName, valueSchemaStr);
    }

    @Override
    public List<String> getStorageNodes(String clusterName){
        checkControllerMastership(clusterName);
        return admin.getInstancesInCluster(clusterName);
    }

    @Override
    public synchronized void stop(String clusterName) {
        // Instead of disconnecting the sub-controller for the given cluster, we should disable it for this controller,
        // then the LEADER->STANDBY and STANDBY->OFFLINE will be triggered, our handler will handle the resource collection.
        List<String> partitionNames = new ArrayList<>();
        partitionNames.add(VeniceDistClusterControllerStateModel.getPartitionNameFromVeniceClusterName(clusterName));
        admin.enablePartition(false, controllerClusterName, controllerName, clusterName, partitionNames);
    }

    @Override
    public ExecutionStatus getOffLineJobStatus(String clusterName, String kafkaTopic) {
        checkControllerMastership(clusterName);
        VeniceJobManager jobManager = getVeniceHelixResource(clusterName).getJobManager();
        return jobManager.getOfflineJobStatus(kafkaTopic);
    }

    // Create the cluster for all of parent controllers if required.
    private void createControllerClusterIfRequired(){
        if(admin.getClusters().contains(controllerClusterName)) {
            logger.info("Cluster  " + controllerClusterName + " already exists. ");
            return;
        }

        boolean isClusterCreated = admin.addCluster(controllerClusterName, false);
        if(isClusterCreated == false) {
            logger.info("Cluster  " + controllerClusterName + " Creation returned false. ");
            return;
        }
        HelixConfigScope configScope = new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.CLUSTER).
            forCluster(controllerClusterName).build();
        Map<String, String> helixClusterProperties = new HashMap<String, String>();
        helixClusterProperties.put(ZKHelixManager.ALLOW_PARTICIPANT_AUTO_JOIN, String.valueOf(true));
        admin.setConfig(configScope, helixClusterProperties);
        admin.addStateModelDef(controllerClusterName, LeaderStandbySMD.name, LeaderStandbySMD.build());
    }

    private void createClusterIfRequired(String clusterName) {
        if(admin.getClusters().contains(clusterName)) {
            logger.info("Cluster  " + clusterName + " already exists. ");
            return;
        }

        boolean isClusterCreated = admin.addCluster(clusterName, false);
        if(isClusterCreated == false) {
            logger.info("Cluster  " + clusterName + " Creation returned false. ");
            return;
        }

        HelixConfigScope configScope = new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.CLUSTER).
                forCluster(clusterName).build();
        Map<String, String> helixClusterProperties = new HashMap<String, String>();
        helixClusterProperties.put(ZKHelixManager.ALLOW_PARTICIPANT_AUTO_JOIN, String.valueOf(true));
        admin.setConfig(configScope, helixClusterProperties);
        logger.info("Cluster  " + clusterName + "  Completed, auto join to true. ");

        admin.addStateModelDef(clusterName, VeniceStateModel.PARTITION_ONLINE_OFFLINE_STATE_MODEL,
            VeniceStateModel.getDefinition());

        admin
            .addResource(controllerClusterName, clusterName, CONTROLLER_CLUSTER_NUMBER_OF_PARTITION, LeaderStandbySMD.name,
                IdealState.RebalanceMode.FULL_AUTO.toString());
        admin.rebalance(controllerClusterName, clusterName, controllerClusterReplica);
    }

    private void throwStoreAlreadyExists(String clusterName, String storeName) {
        String errorMessage = "Store:" + storeName + " already exists. Can not add it to cluster:" + clusterName;
        logger.info(errorMessage);
        throw new VeniceException(errorMessage);
    }

    private void throwStoreDoesNotExist(String clusterName, String storeName) {
        String errorMessage = "Store:" + storeName + " does not exist in cluster:" + clusterName;
        logger.info(errorMessage);
        throw new VeniceException(errorMessage);
    }

    private void throwResourceAlreadyExists(String resourceName) {
        String errorMessage = "Resource:" + resourceName + " already exists, Can not add it to Helix.";
        logger.info(errorMessage);
        throw new VeniceException(errorMessage);
    }

    private void throwVersionAlreadyExists(String storeName, int version) {
        String errorMessage =
            "Version" + version + " already exists in Store:" + storeName + ". Can not add it to store.";
        logger.info(errorMessage);
        throw new VeniceException(errorMessage);
    }

    private void throwClusterNotInitialized(String clusterName) {
        String errorMessage = "Cluster " + clusterName + " is not initialized.";
        logger.info(errorMessage);
        throw new VeniceException(errorMessage);
    }

    @Override
    public String getKafkaBootstrapServers() {
        return this.kafkaBootstrapServers;
    }

    @Override
    public TopicManager getTopicManager() {
        return this.topicManager;
    }

    @Override
    public synchronized boolean isMasterController(String clusterName) {
        VeniceDistClusterControllerStateModel model = controllerStateModelFactory.getModel(clusterName);
        if (model == null ) {
            throwClusterNotInitialized(clusterName);
        }
        return model.getCurrentState().equals(LeaderStandbySMD.States.LEADER.toString());
    }

  /**
   * Calculate number of partition for given store by give size.
   *
   * @param clusterName
   * @param storeName
   * @param storeSize
   * @return
   */
    @Override
    public int calculateNumberOfPartitions(String clusterName, String storeName, long storeSize) {
        checkControllerMastership(clusterName);
        VeniceControllerClusterConfig config = getVeniceHelixResource(clusterName).getConfig();
        return PartitionCountUtils.calculatePartitionCount(clusterName, storeName, storeSize,
            getVeniceHelixResource(clusterName).getMetadataRepository(),
            getVeniceHelixResource(clusterName).getRoutingDataRepository(), config.getPartitionSize(),
            config.getNumberOfPartition(), config.getMaxNumberOfPartition());
  }

    @Override
    public int getReplicaFactor(String clusterName, String storeName) {
        //TODO if there is special config for the given store, use that value.
        return getVeniceHelixResource(clusterName).getConfig().getReplicaFactor();
    }

    @Override
    public List<Replica> getBootstrapReplicas(String clusterName, String kafkaTopic) {
        checkControllerMastership(clusterName);
        List<Replica> replicas = new ArrayList<>();
        PartitionAssignment partitionAssignment = getVeniceHelixResource(clusterName).getRoutingDataRepository().getPartitionAssignments(kafkaTopic);
        for(Partition partition:partitionAssignment.getAllPartitions()){
            addInstancesToReplicaList(replicas, partition.getBootstrapInstances(), kafkaTopic, partition.getId(), HelixState.BOOTSTRAP_STATE);
        }
        return replicas;
    }

    @Override
    public List<Replica> getErrorReplicas(String clusterName, String kafkaTopic) {
        checkControllerMastership(clusterName);
        List<Replica> replicas = new ArrayList<>();
        PartitionAssignment partitionAssignment = getVeniceHelixResource(clusterName).getRoutingDataRepository().getPartitionAssignments(kafkaTopic);
        for(Partition partition:partitionAssignment.getAllPartitions()){
            addInstancesToReplicaList(replicas, partition.getErrorInstances(), kafkaTopic, partition.getId(), HelixState.ERROR_STATE);
        }
        return replicas;
    }

    @Override
    public List<Replica> getReplicas(String clusterName, String kafkaTopic) {
        checkControllerMastership(clusterName);
        List<Replica> replicas = new ArrayList<>();
        PartitionAssignment partitionAssignment = getVeniceHelixResource(clusterName).getRoutingDataRepository().getPartitionAssignments(kafkaTopic);
        for(Partition partition:partitionAssignment.getAllPartitions()){
            addInstancesToReplicaList(replicas, partition.getErrorInstances(), kafkaTopic, partition.getId(), HelixState.ERROR_STATE);
            addInstancesToReplicaList(replicas, partition.getBootstrapInstances(), kafkaTopic, partition.getId(), HelixState.BOOTSTRAP_STATE);
            addInstancesToReplicaList(replicas, partition.getReadyToServeInstances(), kafkaTopic, partition.getId(), HelixState.ONLINE_STATE);
        }
        return replicas;
    }

    private void addInstancesToReplicaList(List<Replica> replicaList, List<Instance> instancesToAdd, String resource, int partitionId, String stateOfAddedReplicas){
        for (Instance instance : instancesToAdd){
            Replica replica = new Replica(instance, partitionId, resource);
            replica.setStatus(stateOfAddedReplicas);
            replicaList.add(replica);
        }
    }

    @Override
    public List<Replica> getReplicasOfStorageNode(String cluster, String instanceId){
        List<Replica> replicas = new ArrayList<>();
        List<String> resources = admin.getResourcesInCluster(cluster);
        for (String resource : resources){
            ExternalView ev = admin.getResourceExternalView(cluster, resource);
            Set<String> partitions = ev.getPartitionSet();
            for (String partition : partitions) {
                Map<String, String> InstanceAndStatusMap = ev.getStateMap(partition);
                for (Map.Entry<String, String> pair : InstanceAndStatusMap.entrySet()){
                    if (pair.getKey().equals(instanceId)){
                        String status = pair.getValue();
                        Instance instance = Instance.fromNodeId(instanceId);
                        Replica replica = new Replica(instance, HelixUtils.getPartitionId(partition), resource);
                        replica.setStatus(status);
                        replicas.add(replica);
                    }
                }
            }
        }
        return replicas;
    }

    @Override
    public boolean isInstanceRemovable(String clusterName, String helixNodeId) {
        checkControllerMastership(clusterName);
        return InstanceStatusDecider.isRemovable(getVeniceHelixResource(clusterName), clusterName, helixNodeId);
    }

    @Override
    public Instance getMasterController(String clusterName) {
        PropertyKey.Builder keyBuilder = new PropertyKey.Builder(clusterName);
        LiveInstance instance = manager.getHelixDataAccessor().getProperty(keyBuilder.controllerLeader());
        if (instance == null) {
            throw new VeniceException("Can not find a master controller in the cluster:" + clusterName);
        } else {
            String instanceId = instance.getId();
            return new Instance(instanceId, Utils.parseHostFromHelixNodeIdentifier(instanceId),
                Utils.parsePortFromHelixNodeIdentifier(instanceId));
        }
    }

    @Override
    public void pauseStore(String clusterName, String storeName) {
        setPausedForStore(clusterName, storeName, true);
    }

    @Override
    public void resumeStore(String clusterName, String storeName) {
        setPausedForStore(clusterName, storeName, false);
    }

    @Override
    public void addInstanceToWhitelist(String clusterName, String helixNodeId) {
        checkControllerMastership(clusterName);
        whitelistAccessor.addInstanceToWhiteList(clusterName, helixNodeId);
    }

    @Override
    public void removeInstanceFromWhiteList(String clusterName, String helixNodeId) {
        checkControllerMastership(clusterName);
        whitelistAccessor.removeInstanceFromWhiteList(clusterName, helixNodeId);
    }

    @Override
    public Set<String> getWhitelist(String clusterName) {
        checkControllerMastership(clusterName);
        return whitelistAccessor.getWhiteList(clusterName);
    }

    private void setPausedForStore(String clusterName, String storeName, boolean paused) {
        checkControllerMastership(clusterName);
        HelixReadWriteStoreRepository repository = getVeniceHelixResource(clusterName).getMetadataRepository();
        repository.lock();
        try {
            Store store = repository.getStore(storeName);
            store.setPaused(paused);
            repository.updateStore(store);
        } finally {
            repository.unLock();
        }
    }

    @Override
    public void close() {
        manager.disconnect();
        zkClient.close();
        IOUtils.closeQuietly(topicManager);
    }

    /**
     * Check whether this controller is master or not. If not, throw the VeniceException to skip the request to
     * this controller.
     *
     * @param clusterName
     */
    private void checkControllerMastership(String clusterName) {
        if (!isMasterController(clusterName)) {
            throw new VeniceException("This controller:" + controllerName + " is not the master of '" + clusterName
                + "'. Can not handle the admin request.");
        }
    }

    protected VeniceHelixResources getVeniceHelixResource(String cluster){
        VeniceHelixResources resources = controllerStateModelFactory.getModel(cluster).getResources();
        if(resources == null){
            throwClusterNotInitialized(cluster);
        }
        return resources;
    }

    public void addConfig(String clusterName,VeniceControllerConfig config){
        controllerStateModelFactory.addClusterConfig(clusterName, config);
    }

    public ZkWhitelistAccessor getWhitelistAccessor() {
        return whitelistAccessor;
    }

    public String getControllerName(){
        return controllerName;
    }
}
