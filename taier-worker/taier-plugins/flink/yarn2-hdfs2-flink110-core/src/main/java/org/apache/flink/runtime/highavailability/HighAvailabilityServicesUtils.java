/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.highavailability;

import com.dtstack.taier.flink.constrant.ConfigConstrant;
import org.apache.flink.shaded.curator.org.apache.curator.framework.CuratorFramework;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.*;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.blob.BlobStoreService;
import org.apache.flink.runtime.blob.BlobUtils;
import org.apache.flink.runtime.dispatcher.Dispatcher;
import org.apache.flink.runtime.highavailability.nonha.embedded.EmbeddedHaServices;
import org.apache.flink.runtime.highavailability.nonha.standalone.StandaloneClientHAServices;
import org.apache.flink.runtime.highavailability.nonha.standalone.StandaloneHaServices;
import org.apache.flink.runtime.highavailability.zookeeper.ZooKeeperClientHAServices;
import org.apache.flink.runtime.highavailability.zookeeper.ZooKeeperHaServices;
import org.apache.flink.runtime.jobmanager.HighAvailabilityMode;
import org.apache.flink.runtime.jobmaster.JobMaster;
import org.apache.flink.runtime.net.SSLUtils;
import org.apache.flink.runtime.resourcemanager.ResourceManager;
import org.apache.flink.runtime.rpc.akka.AkkaRpcServiceUtils;
import org.apache.flink.runtime.security.DynamicConfiguration;
import org.apache.flink.runtime.security.KerberosUtils;
import org.apache.flink.runtime.util.ZooKeeperUtils;
import org.apache.flink.util.ConfigurationException;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.InstantiationUtil;

import javax.security.auth.login.AppConfigurationEntry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.Executor;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.StringUtils.isNullOrWhitespaceOnly;

/**
 * Utils class to instantiate {@link HighAvailabilityServices} implementations.
 */
public class HighAvailabilityServicesUtils {

    private static final Object lock = new Object();

    public static HighAvailabilityServices createAvailableOrEmbeddedServices(
            Configuration config,
            Executor executor) throws Exception {
        HighAvailabilityMode highAvailabilityMode = HighAvailabilityMode.fromConfig(config);

        switch (highAvailabilityMode) {
            case NONE:
                return new EmbeddedHaServices(executor);

            case ZOOKEEPER:
                BlobStoreService blobStoreService = BlobUtils.createBlobStoreFromConfig(config);

                CuratorFramework curatorFramework = ZooKeeperUtils.startCuratorFramework(config);
                return new ZooKeeperHaServices(
                        curatorFramework,
                        executor,
                        config,
                        blobStoreService);

            case FACTORY_CLASS:
                return createCustomHAServices(config, executor);

            default:
                throw new Exception("High availability mode " + highAvailabilityMode + " is not supported.");
        }
    }

    public static HighAvailabilityServices createHighAvailabilityServices(
            Configuration configuration,
            Executor executor,
            AddressResolution addressResolution) throws Exception {

        HighAvailabilityMode highAvailabilityMode = HighAvailabilityMode.fromConfig(configuration);

        switch (highAvailabilityMode) {
            case NONE:
                final Tuple2<String, Integer> hostnamePort = getJobManagerAddress(configuration);

                final String jobManagerRpcUrl = AkkaRpcServiceUtils.getRpcUrl(
                        hostnamePort.f0,
                        hostnamePort.f1,
                        JobMaster.JOB_MANAGER_NAME,
                        addressResolution,
                        configuration);
                final String resourceManagerRpcUrl = AkkaRpcServiceUtils.getRpcUrl(
                        hostnamePort.f0,
                        hostnamePort.f1,
                        ResourceManager.RESOURCE_MANAGER_NAME,
                        addressResolution,
                        configuration);
                final String dispatcherRpcUrl = AkkaRpcServiceUtils.getRpcUrl(
                        hostnamePort.f0,
                        hostnamePort.f1,
                        Dispatcher.DISPATCHER_NAME,
                        addressResolution,
                        configuration);
                final String webMonitorAddress = getWebMonitorAddress(
                        configuration,
                        addressResolution);

                return new StandaloneHaServices(
                        resourceManagerRpcUrl,
                        dispatcherRpcUrl,
                        jobManagerRpcUrl,
                        webMonitorAddress);
            case ZOOKEEPER:
                BlobStoreService blobStoreService = BlobUtils.createBlobStoreFromConfig(configuration);

                return new ZooKeeperHaServices(
                        ZooKeeperUtils.startCuratorFramework(configuration),
                        executor,
                        configuration,
                        blobStoreService);

            case FACTORY_CLASS:
                return createCustomHAServices(configuration, executor);

            default:
                throw new Exception("Recovery mode " + highAvailabilityMode + " is not supported.");
        }
    }

    public static ClientHighAvailabilityServices createClientHAService(Configuration configuration) throws Exception {
        HighAvailabilityMode highAvailabilityMode = HighAvailabilityMode.fromConfig(configuration);

        switch (highAvailabilityMode) {
            case NONE:
                final String webMonitorAddress = getWebMonitorAddress(configuration, AddressResolution.TRY_ADDRESS_RESOLUTION);
                return new StandaloneClientHAServices(webMonitorAddress);
            case ZOOKEEPER:

                boolean appendJaasEnable = configuration.getBoolean(ConfigConstrant.ZOOKEEPER_CLIENT_APPEND_JAAS_ENABLE, false);
                if (appendJaasEnable) {

                    String zookeeperContestName = configuration.get(SecurityOptions.ZOOKEEPER_SASL_LOGIN_CONTEXT_NAME);
                    String keytabPath = configuration.get(SecurityOptions.KERBEROS_LOGIN_KEYTAB);
                    String principal = configuration.get(SecurityOptions.KERBEROS_LOGIN_PRINCIPAL);

                    synchronized (lock) {
                        javax.security.auth.login.Configuration priorConfig = javax.security.auth.login.Configuration.getConfiguration();
                        // construct a dynamic JAAS configuration
                        DynamicConfiguration currentConfig = new DynamicConfiguration(priorConfig);
                        // wire up the configured JAAS login contexts to use the krb5 entries
                        AppConfigurationEntry krb5Entry = KerberosUtils.keytabEntry(keytabPath, principal);
                        currentConfig.addAppConfigurationEntry(zookeeperContestName, krb5Entry);
                        javax.security.auth.login.Configuration.setConfiguration(currentConfig);
                    }
                }

                final CuratorFramework client = ZooKeeperUtils.startCuratorFramework(configuration);
                return new ZooKeeperClientHAServices(client, configuration);
            case FACTORY_CLASS:
                return createCustomClientHAServices(configuration);
            default:
                throw new Exception("Recovery mode " + highAvailabilityMode + " is not supported.");
        }
    }

    /**
     * Returns the JobManager's hostname and port extracted from the given
     * {@link Configuration}.
     *
     * @param configuration Configuration to extract the JobManager's address from
     * @return The JobManager's hostname and port
     * @throws ConfigurationException if the JobManager's address cannot be extracted from the configuration
     */
    public static Tuple2<String, Integer> getJobManagerAddress(Configuration configuration) throws ConfigurationException {

        final String hostname = configuration.getString(JobManagerOptions.ADDRESS);
        final int port = configuration.getInteger(JobManagerOptions.PORT);

        if (hostname == null) {
            throw new ConfigurationException("Config parameter '" + JobManagerOptions.ADDRESS +
                    "' is missing (hostname/address of JobManager to connect to).");
        }

        if (port <= 0 || port >= 65536) {
            throw new ConfigurationException("Invalid value for '" + JobManagerOptions.PORT +
                    "' (port of the JobManager actor system) : " + port +
                    ".  it must be greater than 0 and less than 65536.");
        }

        return Tuple2.of(hostname, port);
    }

    /**
     * Get address of web monitor from configuration.
     *
     * @param configuration Configuration contains those for WebMonitor.
     * @param resolution Whether to try address resolution of the given hostname or not.
     *                   This allows to fail fast in case that the hostname cannot be resolved.
     * @return Address of WebMonitor.
     */
    public static String getWebMonitorAddress(
            Configuration configuration,
            HighAvailabilityServicesUtils.AddressResolution resolution) throws UnknownHostException {
        final String address = checkNotNull(configuration.getString(RestOptions.ADDRESS), "%s must be set", RestOptions.ADDRESS.key());

        if (resolution == HighAvailabilityServicesUtils.AddressResolution.TRY_ADDRESS_RESOLUTION) {
            // Fail fast if the hostname cannot be resolved
            //noinspection ResultOfMethodCallIgnored
            InetAddress.getByName(address);
        }

        final int port = configuration.getInteger(RestOptions.PORT);
        final boolean enableSSL = SSLUtils.isRestSSLEnabled(configuration);
        final String protocol = enableSSL ? "https://" : "http://";

        return String.format("%s%s:%s", protocol, address, port);
    }

    /**
     * Gets the cluster high available storage path from the provided configuration.
     *
     * <p>The format is {@code HA_STORAGE_PATH/HA_CLUSTER_ID}.
     *
     * @param configuration containing the configuration values
     * @return Path under which all highly available cluster artifacts are being stored
     */
    public static Path getClusterHighAvailableStoragePath(Configuration configuration) {
        final String storagePath = configuration.getValue(
                HighAvailabilityOptions.HA_STORAGE_PATH);

        if (isNullOrWhitespaceOnly(storagePath)) {
            throw new IllegalConfigurationException("Configuration is missing the mandatory parameter: " +
                    HighAvailabilityOptions.HA_STORAGE_PATH);
        }

        final Path path;
        try {
            path = new Path(storagePath);
        } catch (Exception e) {
            throw new IllegalConfigurationException("Invalid path for highly available storage (" +
                    HighAvailabilityOptions.HA_STORAGE_PATH.key() + ')', e);
        }

        final String clusterId = configuration.getValue(HighAvailabilityOptions.HA_CLUSTER_ID);

        final Path clusterStoragePath;

        try {
            clusterStoragePath = new Path(path, clusterId);
        } catch (Exception e) {
            throw new IllegalConfigurationException(
                    String.format("Cannot create cluster high available storage path '%s/%s'. This indicates that an invalid cluster id (%s) has been specified.",
                            storagePath,
                            clusterId,
                            HighAvailabilityOptions.HA_CLUSTER_ID.key()),
                    e);
        }
        return clusterStoragePath;
    }

    private static HighAvailabilityServices createCustomHAServices(Configuration config, Executor executor) throws FlinkException {
        final HighAvailabilityServicesFactory highAvailabilityServicesFactory = loadCustomHighAvailabilityServicesFactory(
                config.getString(HighAvailabilityOptions.HA_MODE));

        try {
            return highAvailabilityServicesFactory.createHAServices(config, executor);
        } catch (Exception e) {
            throw new FlinkException(
                    String.format(
                            "Could not create the ha services from the instantiated HighAvailabilityServicesFactory %s.",
                            highAvailabilityServicesFactory.getClass().getName()),
                    e);
        }
    }

    private static HighAvailabilityServicesFactory loadCustomHighAvailabilityServicesFactory(String highAvailabilityServicesFactoryClassName) throws FlinkException {
        final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        return InstantiationUtil.instantiate(
                highAvailabilityServicesFactoryClassName,
                HighAvailabilityServicesFactory.class,
                classLoader);
    }

    private static ClientHighAvailabilityServices createCustomClientHAServices(Configuration config) throws FlinkException {
        final HighAvailabilityServicesFactory highAvailabilityServicesFactory = loadCustomHighAvailabilityServicesFactory(
                config.getString(HighAvailabilityOptions.HA_MODE));

        try {
            return highAvailabilityServicesFactory.createClientHAServices(config);
        } catch (Exception e) {
            throw new FlinkException(
                    String.format(
                            "Could not create the client ha services from the instantiated HighAvailabilityServicesFactory %s.",
                            highAvailabilityServicesFactory.getClass().getName()),
                    e);
        }
    }

    /**
     * Enum specifying whether address resolution should be tried or not when creating the
     * {@link HighAvailabilityServices}.
     */
    public enum AddressResolution {
        TRY_ADDRESS_RESOLUTION,
        NO_ADDRESS_RESOLUTION
    }
}
