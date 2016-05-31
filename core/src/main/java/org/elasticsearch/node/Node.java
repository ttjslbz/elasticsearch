/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.node;

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.Build;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchTimeoutException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionModule;
import org.elasticsearch.cache.recycler.PageCacheRecycler;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.node.NodeClientModule;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.cluster.ClusterNameModule;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateObserver;
import org.elasticsearch.cluster.MasterNodeChangePredicate;
import org.elasticsearch.cluster.NodeConnectionsService;
import org.elasticsearch.cluster.action.index.MappingUpdatedAction;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeService;
import org.elasticsearch.cluster.routing.RoutingService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.StopWatch;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.discovery.DiscoveryModule;
import org.elasticsearch.discovery.DiscoverySettings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.EnvironmentModule;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.env.NodeEnvironmentModule;
import org.elasticsearch.gateway.GatewayAllocator;
import org.elasticsearch.gateway.GatewayModule;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.http.HttpServer;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.indices.IndicesModule;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.analysis.AnalysisModule;
import org.elasticsearch.indices.breaker.CircuitBreakerModule;
import org.elasticsearch.indices.cluster.IndicesClusterStateService;
import org.elasticsearch.indices.store.IndicesStore;
import org.elasticsearch.indices.ttl.IndicesTTLService;
import org.elasticsearch.monitor.MonitorService;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.node.internal.InternalSettingsPreparer;
import org.elasticsearch.node.service.NodeService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.PluginsModule;
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.repositories.RepositoriesModule;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.snapshots.SnapshotShardsService;
import org.elasticsearch.snapshots.SnapshotsService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.threadpool.ThreadPoolModule;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.tribe.TribeModule;
import org.elasticsearch.tribe.TribeService;
import org.elasticsearch.watcher.ResourceWatcherModule;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;

/**
 * A node represent a node within a cluster (<tt>cluster.name</tt>). The {@link #client()} can be used
 * in order to use a {@link Client} to perform actions/operations against the cluster.
 */
public class Node implements Closeable {

    public static final Setting<Boolean> WRITE_PORTS_FIELD_SETTING =
        Setting.boolSetting("node.portsfile", false, Property.NodeScope);
    public static final Setting<Boolean> NODE_CLIENT_SETTING =
        Setting.boolSetting("node.client", false, Property.NodeScope);
    public static final Setting<Boolean> NODE_DATA_SETTING = Setting.boolSetting("node.data", true, Property.NodeScope);
    public static final Setting<Boolean> NODE_MASTER_SETTING =
        Setting.boolSetting("node.master", true, Property.NodeScope);
    public static final Setting<Boolean> NODE_LOCAL_SETTING =
        Setting.boolSetting("node.local", false, Property.NodeScope);
    public static final Setting<String> NODE_MODE_SETTING =
        new Setting<>("node.mode", "network", Function.identity(), Property.NodeScope);
    public static final Setting<Boolean> NODE_INGEST_SETTING =
        Setting.boolSetting("node.ingest", true, Property.NodeScope);
    public static final Setting<String> NODE_NAME_SETTING = Setting.simpleString("node.name", Property.NodeScope);
    // this sucks that folks can mistype data, master or ingest and get away with it.
    // TODO: we should move this to node.attribute.${name} = ${value} instead.
    public static final Setting<Settings> NODE_ATTRIBUTES = Setting.groupSetting("node.", Property.NodeScope);


    private static final String CLIENT_TYPE = "node";
    private final Lifecycle lifecycle = new Lifecycle();
    private final Injector injector;
    private final Settings settings;
    private final Environment environment;
    private final PluginsService pluginsService;
    private final Client client;

    /**
     * Constructs a node with the given settings.
     *
     * @param preparedSettings Base settings to configure the node with
     */
    public Node(Settings preparedSettings) {
        this(InternalSettingsPreparer.prepareEnvironment(preparedSettings, null), Version.CURRENT, Collections.<Class<? extends Plugin>>emptyList());
    }

    protected Node(Environment tmpEnv, Version version, Collection<Class<? extends Plugin>> classpathPlugins) {
        Settings tmpSettings = settingsBuilder().put(tmpEnv.settings())
                .put(Client.CLIENT_TYPE_SETTING_S.getKey(), CLIENT_TYPE).build();
        tmpSettings = TribeService.processSettings(tmpSettings);

        ESLogger logger = Loggers.getLogger(Node.class, NODE_NAME_SETTING.get(tmpSettings));
        final String displayVersion = version + (Build.CURRENT.isSnapshot() ? "-SNAPSHOT" : "");
        logger.info("version[{}], pid[{}], build[{}/{}]", displayVersion, JvmInfo.jvmInfo().pid(), Build.CURRENT.shortHash(), Build.CURRENT.date());

        logger.info("initializing ...");

        if (logger.isDebugEnabled()) {
            logger.debug("using config [{}], data [{}], logs [{}], plugins [{}]",
                    tmpEnv.configFile(), Arrays.toString(tmpEnv.dataFiles()), tmpEnv.logsFile(), tmpEnv.pluginsFile());
        }

        this.pluginsService = new PluginsService(tmpSettings, tmpEnv.modulesFile(), tmpEnv.pluginsFile(), classpathPlugins);
        this.settings = pluginsService.updatedSettings();
        // create the environment based on the finalized (processed) view of the settings
        this.environment = new Environment(this.settings());

        final NodeEnvironment nodeEnvironment;
        try {
            nodeEnvironment = new NodeEnvironment(this.settings, this.environment);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to created node environment", ex);
        }
        final NetworkService networkService = new NetworkService(settings);
        final ThreadPool threadPool = new ThreadPool(settings);
        NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry();
        boolean success = false;
        try {
            final MonitorService monitorService = new MonitorService(settings, nodeEnvironment, threadPool);
            ModulesBuilder modules = new ModulesBuilder();
            modules.add(new Version.Module(version));
            modules.add(new CircuitBreakerModule(settings));
            // plugin modules must be added here, before others or we can get crazy injection errors...
            for (Module pluginModule : pluginsService.nodeModules()) {
                modules.add(pluginModule);
            }
            modules.add(new PluginsModule(pluginsService));
            SettingsModule settingsModule = new SettingsModule(this.settings);
            modules.add(settingsModule);
            modules.add(new EnvironmentModule(environment));
            modules.add(new NodeModule(this, monitorService));
            modules.add(new NetworkModule(networkService, settings, false, namedWriteableRegistry));
            ScriptModule scriptModule = new ScriptModule();
            modules.add(scriptModule);
            modules.add(new NodeEnvironmentModule(nodeEnvironment));
            modules.add(new ClusterNameModule(this.settings));
            modules.add(new ThreadPoolModule(threadPool));
            modules.add(new DiscoveryModule(this.settings));
            modules.add(new ClusterModule(this.settings));
            modules.add(new IndicesModule());
            modules.add(new SearchModule(settings, namedWriteableRegistry));
            modules.add(new ActionModule(DiscoveryNode.ingestNode(settings), false));
            modules.add(new GatewayModule(settings));
            modules.add(new NodeClientModule());
            modules.add(new ResourceWatcherModule());
            modules.add(new RepositoriesModule());
            modules.add(new TribeModule());
            modules.add(new AnalysisModule(environment));

            pluginsService.processModules(modules);
            scriptModule.prepareSettings(settingsModule);
            injector = modules.createInjector();

            client = injector.getInstance(Client.class);
            threadPool.setClusterSettings(injector.getInstance(ClusterSettings.class));
            success = true;
        } catch (IOException ex) {
            throw new ElasticsearchException("failed to bind service", ex);
        } finally {
            if (!success) {
                nodeEnvironment.close();
                ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS);
            }
        }

        logger.info("initialized");
    }

    /**
     * The settings that were used to create the node.
     */
    public Settings settings() {
        return this.settings;
    }

    /**
     * A client that can be used to execute actions (operations) against the cluster.
     */
    public Client client() {
        return client;
    }

    /**
     * Returns the environment of the node
     */
    public Environment getEnvironment() {
        return environment;
    }

    /**
     * Start the node. If the node is already started, this method is no-op.
     */
    public Node start() {
        if (!lifecycle.moveToStarted()) {
            return this;
        }

        ESLogger logger = Loggers.getLogger(Node.class, NODE_NAME_SETTING.get(settings));
        logger.info("starting ...");
        // hack around dependency injection problem (for now...)
        injector.getInstance(Discovery.class).setRoutingService(injector.getInstance(RoutingService.class));
        for (Class<? extends LifecycleComponent> plugin : pluginsService.nodeServices()) {
            injector.getInstance(plugin).start();
        }

        injector.getInstance(MappingUpdatedAction.class).setClient(client);
        injector.getInstance(IndicesService.class).start();
        injector.getInstance(IndicesClusterStateService.class).start();
        injector.getInstance(IndicesTTLService.class).start();
        injector.getInstance(SnapshotsService.class).start();
        injector.getInstance(SnapshotShardsService.class).start();
        injector.getInstance(RoutingService.class).start();
        injector.getInstance(SearchService.class).start();
        injector.getInstance(MonitorService.class).start();
        injector.getInstance(RestController.class).start();

        final ClusterService clusterService = injector.getInstance(ClusterService.class);

        final NodeConnectionsService nodeConnectionsService = injector.getInstance(NodeConnectionsService.class);
        nodeConnectionsService.start();
        clusterService.setNodeConnectionsService(nodeConnectionsService);

        // TODO hack around circular dependencies problems
        injector.getInstance(GatewayAllocator.class).setReallocation(clusterService, injector.getInstance(RoutingService.class));

        injector.getInstance(ResourceWatcherService.class).start();
        injector.getInstance(GatewayService.class).start();
        Discovery discovery = injector.getInstance(Discovery.class);
        clusterService.addInitialStateBlock(discovery.getDiscoverySettings().getNoMasterBlock());
        clusterService.setClusterStatePublisher(discovery::publish);

        // start before the cluster service since it adds/removes initial Cluster state blocks
        final TribeService tribeService = injector.getInstance(TribeService.class);
        tribeService.start();


        // Start the transport service now so the publish address will be added to the local disco node in ClusterService
        TransportService transportService = injector.getInstance(TransportService.class);
        transportService.start();
        DiscoveryNode localNode = injector.getInstance(DiscoveryNodeService.class)
                .buildLocalNode(transportService.boundAddress().publishAddress());

        // TODO: need to find a cleaner way to start/construct a service with some initial parameters,
        // playing nice with the life cycle interfaces
        clusterService.setLocalNode(localNode);
        transportService.setLocalNode(localNode);
        clusterService.add(transportService.getTaskManager());

        clusterService.start();

        // start after cluster service so the local disco is known
        discovery.start();
        transportService.acceptIncomingRequests();
        discovery.startInitialJoin();
        // tribe nodes don't have a master so we shouldn't register an observer
        if (DiscoverySettings.INITIAL_STATE_TIMEOUT_SETTING.get(settings).millis() > 0) {
            final ThreadPool thread = injector.getInstance(ThreadPool.class);
            ClusterStateObserver observer = new ClusterStateObserver(clusterService, null, logger, thread.getThreadContext());
            if (observer.observedState().nodes().masterNodeId() == null) {
                final CountDownLatch latch = new CountDownLatch(1);
                observer.waitForNextChange(new ClusterStateObserver.Listener() {
                    @Override
                    public void onNewClusterState(ClusterState state) { latch.countDown(); }

                    @Override
                    public void onClusterServiceClose() {
                        latch.countDown();
                    }

                    @Override
                    public void onTimeout(TimeValue timeout) {
                        logger.warn("timed out while waiting for initial discovery state - timeout: {}",
                            DiscoverySettings.INITIAL_STATE_TIMEOUT_SETTING.get(settings));
                        latch.countDown();
                    }
                }, MasterNodeChangePredicate.INSTANCE, DiscoverySettings.INITIAL_STATE_TIMEOUT_SETTING.get(settings));

                try {
                    latch.await();
                } catch (InterruptedException e) {
                    throw new ElasticsearchTimeoutException("Interrupted while waiting for initial discovery state");
                }
            }
        }

        if (settings.getAsBoolean("http.enabled", true)) {
            injector.getInstance(HttpServer.class).start();
        }

        // start nodes now, after the http server, because it may take some time
        tribeService.startNodes();


        if (WRITE_PORTS_FIELD_SETTING.get(settings)) {
            if (settings.getAsBoolean("http.enabled", true)) {
                HttpServerTransport http = injector.getInstance(HttpServerTransport.class);
                writePortsFile("http", http.boundAddress());
            }
            TransportService transport = injector.getInstance(TransportService.class);
            writePortsFile("transport", transport.boundAddress());
        }

        logger.info("started");

        return this;
    }

    private Node stop() {
        if (!lifecycle.moveToStopped()) {
            return this;
        }
        ESLogger logger = Loggers.getLogger(Node.class, NODE_NAME_SETTING.get(settings));
        logger.info("stopping ...");

        injector.getInstance(TribeService.class).stop();
        injector.getInstance(ResourceWatcherService.class).stop();
        if (settings.getAsBoolean("http.enabled", true)) {
            injector.getInstance(HttpServer.class).stop();
        }

        injector.getInstance(SnapshotsService.class).stop();
        injector.getInstance(SnapshotShardsService.class).stop();
        // stop any changes happening as a result of cluster state changes
        injector.getInstance(IndicesClusterStateService.class).stop();
        // we close indices first, so operations won't be allowed on it
        injector.getInstance(IndicesTTLService.class).stop();
        injector.getInstance(RoutingService.class).stop();
        injector.getInstance(ClusterService.class).stop();
        injector.getInstance(Discovery.class).stop();
        injector.getInstance(NodeConnectionsService.class).stop();
        injector.getInstance(MonitorService.class).stop();
        injector.getInstance(GatewayService.class).stop();
        injector.getInstance(SearchService.class).stop();
        injector.getInstance(RestController.class).stop();
        injector.getInstance(TransportService.class).stop();

        for (Class<? extends LifecycleComponent> plugin : pluginsService.nodeServices()) {
            injector.getInstance(plugin).stop();
        }
        // we should stop this last since it waits for resources to get released
        // if we had scroll searchers etc or recovery going on we wait for to finish.
        injector.getInstance(IndicesService.class).stop();
        logger.info("stopped");

        return this;
    }

    // During concurrent close() calls we want to make sure that all of them return after the node has completed it's shutdown cycle.
    // If not, the hook that is added in Bootstrap#setup() will be useless: close() might not be executed, in case another (for example api) call
    // to close() has already set some lifecycles to stopped. In this case the process will be terminated even if the first call to close() has not finished yet.
    @Override
    public synchronized void close() throws IOException {
        if (lifecycle.started()) {
            stop();
        }
        if (!lifecycle.moveToClosed()) {
            return;
        }

        ESLogger logger = Loggers.getLogger(Node.class, NODE_NAME_SETTING.get(settings));
        logger.info("closing ...");
        List<Closeable> toClose = new ArrayList<>();
        StopWatch stopWatch = new StopWatch("node_close");
        toClose.add(() -> stopWatch.start("tribe"));
        toClose.add(injector.getInstance(TribeService.class));
        toClose.add(() -> stopWatch.stop().start("node_service"));
        toClose.add(injector.getInstance(NodeService.class));
        toClose.add(() -> stopWatch.stop().start("http"));
        if (settings.getAsBoolean("http.enabled", true)) {
            toClose.add(injector.getInstance(HttpServer.class));
        }
        toClose.add(() -> stopWatch.stop().start("snapshot_service"));
        toClose.add(injector.getInstance(SnapshotsService.class));
        toClose.add(injector.getInstance(SnapshotShardsService.class));
        toClose.add(() -> stopWatch.stop().start("client"));
        Releasables.close(injector.getInstance(Client.class));
        toClose.add(() -> stopWatch.stop().start("indices_cluster"));
        toClose.add(injector.getInstance(IndicesClusterStateService.class));
        toClose.add(() -> stopWatch.stop().start("indices"));
        toClose.add(injector.getInstance(IndicesTTLService.class));
        toClose.add(injector.getInstance(IndicesService.class));
        // close filter/fielddata caches after indices
        toClose.add(injector.getInstance(IndicesStore.class));
        toClose.add(() -> stopWatch.stop().start("routing"));
        toClose.add(injector.getInstance(RoutingService.class));
        toClose.add(() -> stopWatch.stop().start("cluster"));
        toClose.add(injector.getInstance(ClusterService.class));
        toClose.add(() -> stopWatch.stop().start("node_connections_service"));
        toClose.add(injector.getInstance(NodeConnectionsService.class));
        toClose.add(() -> stopWatch.stop().start("discovery"));
        toClose.add(injector.getInstance(Discovery.class));
        toClose.add(() -> stopWatch.stop().start("monitor"));
        toClose.add(injector.getInstance(MonitorService.class));
        toClose.add(() -> stopWatch.stop().start("gateway"));
        toClose.add(injector.getInstance(GatewayService.class));
        toClose.add(() -> stopWatch.stop().start("search"));
        toClose.add(injector.getInstance(SearchService.class));
        toClose.add(() -> stopWatch.stop().start("rest"));
        toClose.add(injector.getInstance(RestController.class));
        toClose.add(() -> stopWatch.stop().start("transport"));
        toClose.add(injector.getInstance(TransportService.class));

        for (Class<? extends LifecycleComponent> plugin : pluginsService.nodeServices()) {
            toClose.add(() -> stopWatch.stop().start("plugin(" + plugin.getName() + ")"));
            toClose.add(injector.getInstance(plugin));
        }

        toClose.add(() -> stopWatch.stop().start("script"));
        toClose.add(injector.getInstance(ScriptService.class));

        toClose.add(() -> stopWatch.stop().start("thread_pool"));
        // TODO this should really use ThreadPool.terminate()
        toClose.add(() -> injector.getInstance(ThreadPool.class).shutdown());
        toClose.add(() -> {
            try {
                injector.getInstance(ThreadPool.class).awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // ignore
            }
        });

        toClose.add(() -> stopWatch.stop().start("thread_pool_force_shutdown"));
        toClose.add(() -> injector.getInstance(ThreadPool.class).shutdownNow());
        toClose.add(() -> stopWatch.stop());


        toClose.add(injector.getInstance(NodeEnvironment.class));
        toClose.add(injector.getInstance(PageCacheRecycler.class));

        if (logger.isTraceEnabled()) {
            logger.trace("Close times for each service:\n{}", stopWatch.prettyPrint());
        }
        IOUtils.close(toClose);
        logger.info("closed");
    }


    /**
     * Returns <tt>true</tt> if the node is closed.
     */
    public boolean isClosed() {
        return lifecycle.closed();
    }

    public Injector injector() {
        return this.injector;
    }

    /** Writes a file to the logs dir containing the ports for the given transport type */
    private void writePortsFile(String type, BoundTransportAddress boundAddress) {
        Path tmpPortsFile = environment.logsFile().resolve(type + ".ports.tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(tmpPortsFile, Charset.forName("UTF-8"))) {
            for (TransportAddress address : boundAddress.boundAddresses()) {
                InetAddress inetAddress = InetAddress.getByName(address.getAddress());
                if (inetAddress instanceof Inet6Address && inetAddress.isLinkLocalAddress()) {
                    // no link local, just causes problems
                    continue;
                }
                writer.write(NetworkAddress.formatAddress(new InetSocketAddress(inetAddress, address.getPort())) + "\n");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write ports file", e);
        }
        Path portsFile = environment.logsFile().resolve(type + ".ports");
        try {
            Files.move(tmpPortsFile, portsFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to rename ports file", e);
        }
    }
}
