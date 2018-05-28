/*
 * Copyright 2015-2017 floragunn GmbH
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.floragunn.searchguard;

import io.netty.handler.ssl.OpenSsl;

import java.io.File;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.http.Header;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.DocWriteResponse.Result;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.admin.cluster.repositories.put.PutRepositoryRequest;
import org.elasticsearch.action.admin.cluster.reroute.ClusterRerouteRequest;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest.AliasActions;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.util.concurrent.ThreadContext.StoredContext;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.indices.InvalidIndexNameException;
import org.elasticsearch.indices.InvalidTypeNameException;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.PluginAwareNode;
import org.elasticsearch.transport.Netty4Plugin;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import com.floragunn.searchguard.action.configupdate.ConfigUpdateAction;
import com.floragunn.searchguard.action.configupdate.ConfigUpdateRequest;
import com.floragunn.searchguard.action.configupdate.ConfigUpdateResponse;
import com.floragunn.searchguard.action.whoami.WhoAmIAction;
import com.floragunn.searchguard.action.whoami.WhoAmIResponse;
import com.floragunn.searchguard.action.whoami.WhoAmIRequest;
import com.floragunn.searchguard.configuration.PrivilegesInterceptorImpl;
import com.floragunn.searchguard.http.HTTPClientCertAuthenticator;
import com.floragunn.searchguard.ssl.util.ExceptionUtils;
import com.floragunn.searchguard.ssl.util.SSLConfigConstants;
import com.floragunn.searchguard.support.ConfigConstants;
import com.floragunn.searchguard.test.DynamicSgConfig;
import com.floragunn.searchguard.test.SingleClusterTest;
import com.floragunn.searchguard.test.helper.cluster.ClusterConfiguration;
import com.floragunn.searchguard.test.helper.file.FileHelper;
import com.floragunn.searchguard.test.helper.rest.RestHelper;
import com.floragunn.searchguard.test.helper.rest.RestHelper.HttpResponse;

public class SlowIntegrationTests extends SingleClusterTest {

    @Test
    public void testCustomInterclusterRequestEvaluator() throws Exception {
        
        final Settings settings = Settings.builder()
                .put(ConfigConstants.SG_INTERCLUSTER_REQUEST_EVALUATOR_CLASS, "com.floragunn.searchguard.AlwaysFalseInterClusterRequestEvaluator")
                .build();
        setup(Settings.EMPTY, null, settings, false,ClusterConfiguration.DEFAULT ,5,1);
        Assert.assertEquals(1, clusterHelper.nodeClient().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getNumberOfNodes());
        Assert.assertEquals(ClusterHealthStatus.GREEN, clusterHelper.nodeClient().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getStatus());
    }

    private ThreadContext newThreadContext(String sslPrincipal) {
        ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
        threadContext.putTransient(ConfigConstants.SG_SSL_PRINCIPAL, sslPrincipal);
        return threadContext;
    }

    @SuppressWarnings("resource")
    @Test
    public void testNodeClientAllowedWithServerCertificate() throws Exception {
        setup();
        Assert.assertEquals(3, clusterHelper.nodeClient().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getNumberOfNodes());
        Assert.assertEquals(ClusterHealthStatus.GREEN, clusterHelper.nodeClient().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getStatus());
    
        
        final Settings tcSettings = Settings.builder()
                .put(minimumSearchGuardSettings(Settings.EMPTY).get(0))
                .put("cluster.name", clusterInfo.clustername)
                .put("node.data", false)
                .put("node.master", false)
                .put("node.ingest", false)
                .put("path.home", ".")
                .putList("discovery.zen.ping.unicast.hosts", clusterInfo.nodeHost+":"+clusterInfo.nodePort)
                .build();
    
        log.debug("Start node client");
        
        try (Node node = new PluginAwareNode(tcSettings, Netty4Plugin.class, SearchGuardPlugin.class).start()) {
            Thread.sleep(50);
            Assert.assertEquals(4, node.client().admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());    
        }
    }
    
    @SuppressWarnings("resource")
    @Test
    public void testNodeClientDisallowedWithNonServerCertificate() throws Exception {
        setup();
        Assert.assertEquals(3, clusterHelper.nodeClient().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getNumberOfNodes());
        Assert.assertEquals(ClusterHealthStatus.GREEN, clusterHelper.nodeClient().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getStatus());
    
        
        final Settings tcSettings = Settings.builder()
                .put(minimumSearchGuardSettings(Settings.EMPTY).get(0))
                .put("cluster.name", clusterInfo.clustername)
                .put("node.data", false)
                .put("node.master", false)
                .put("node.ingest", false)
                .put("path.home", ".")
                .put("searchguard.ssl.transport.keystore_filepath", FileHelper.getAbsoluteFilePathFromClassPath("kirk-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"kirk")
                .build();
    
        log.debug("Start node client");
        
        try (Node node = new PluginAwareNode(tcSettings, Netty4Plugin.class, SearchGuardPlugin.class).start()) {
            Thread.sleep(50);
            Assert.assertEquals(1, node.client().admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());    
        }
    }
    
    @SuppressWarnings("resource")
    @Test
    public void testNodeClientDisallowedWithNonServerCertificate2() throws Exception {
        setup();
        Assert.assertEquals(3, clusterHelper.nodeClient().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getNumberOfNodes());
        Assert.assertEquals(ClusterHealthStatus.GREEN, clusterHelper.nodeClient().admin().cluster().health(new ClusterHealthRequest().waitForGreenStatus()).actionGet().getStatus());
     
        final Settings tcSettings = Settings.builder()
                .put(minimumSearchGuardSettings(Settings.EMPTY).get(0))
                .put("cluster.name", clusterInfo.clustername)
                .put("node.data", false)
                .put("node.master", false)
                .put("node.ingest", false)
                .put("path.home", ".")
                .put("searchguard.ssl.transport.keystore_filepath", FileHelper.getAbsoluteFilePathFromClassPath("spock-keystore.jks"))
                .put(SSLConfigConstants.SEARCHGUARD_SSL_TRANSPORT_KEYSTORE_ALIAS,"spock")
                .build();
    
        log.debug("Start node client");
        
        try (Node node = new PluginAwareNode(tcSettings, Netty4Plugin.class, SearchGuardPlugin.class).start()) {
            Thread.sleep(50);
            Assert.assertEquals(1, node.client().admin().cluster().nodesInfo(new NodesInfoRequest()).actionGet().getNodes().size());    
        }
    }

}
