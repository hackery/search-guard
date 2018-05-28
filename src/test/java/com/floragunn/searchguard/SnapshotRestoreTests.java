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

public class SnapshotRestoreTests extends SingleClusterTest {

    private ThreadContext newThreadContext(String sslPrincipal) {
        ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
        threadContext.putTransient(ConfigConstants.SG_SSL_PRINCIPAL, sslPrincipal);
        return threadContext;
    }

    @Test
    public void testSnapshot() throws Exception {
    
        final Settings settings = Settings.builder()
                .putList("path.repo", repositoryPath.getRoot().getAbsolutePath())
                .put("searchguard.enable_snapshot_restore_privilege", true)
                .put("searchguard.check_snapshot_restore_write_privileges", false)
                .build();
    
        setup(settings);
    
        try (TransportClient tc = getInternalTransportClient()) {    
            tc.index(new IndexRequest("vulcangov").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
                
            tc.admin().cluster().putRepository(new PutRepositoryRequest("vulcangov").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/vulcangov"))).actionGet();
            tc.admin().cluster().createSnapshot(new CreateSnapshotRequest("vulcangov", "vulcangov_1").indices("vulcangov").includeGlobalState(true).waitForCompletion(true)).actionGet();
    
            tc.admin().cluster().putRepository(new PutRepositoryRequest("searchguard").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/searchguard"))).actionGet();
            tc.admin().cluster().createSnapshot(new CreateSnapshotRequest("searchguard", "searchguard_1").indices("searchguard").includeGlobalState(false).waitForCompletion(true)).actionGet();
    
            tc.admin().cluster().putRepository(new PutRepositoryRequest("all").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/all"))).actionGet();
            tc.admin().cluster().createSnapshot(new CreateSnapshotRequest("all", "all_1").indices("*").includeGlobalState(false).waitForCompletion(true)).actionGet();
        }
    
        RestHelper rh = nonSslRestHelper();
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/vulcangov", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/vulcangov/vulcangov_1", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_$1\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"include_global_state\": true, \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_with_global_state_$1\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","", encodeBasicHeader("worf", "worf")).getStatusCode());
        // Try to restore vulcangov index as searchguard index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"indices\": \"vulcangov\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Try to restore searchguard index.
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/searchguard", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/searchguard/searchguard_1", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/searchguard/searchguard_1/_restore?wait_for_completion=true","", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Try to restore searchguard index as serchguard_copy index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/searchguard/searchguard_1/_restore?wait_for_completion=true","{ \"indices\": \"searchguard\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard_copy\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Try to restore all indices.
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/all", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/all/all_1", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/all_1/_restore?wait_for_completion=true","", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Try to restore searchguard index as serchguard_copy index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/all_1/_restore?wait_for_completion=true","{ \"indices\": \"vulcangov\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Try to restore searchguard index as serchguard_copy index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/all_1/_restore?wait_for_completion=true","{ \"indices\": \"searchguard\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard_copy\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Try to restore a unknown snapshot
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/unknown-snapshot/_restore?wait_for_completion=true", "", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Assert.assertEquals(HttpStatus.SC_FORBIDDEN, executePostRequest("_snapshot/all/unknown-snapshot/_restore?wait_for_completion=true","{ \"indices\": \"the-unknown-index\" }", encodeBasicHeader("nagilum", "nagilum"))).getStatusCode());
    }

    @Test
    public void testSnapshotCheckWritePrivileges() throws Exception {
    
        final Settings settings = Settings.builder()
                .putList("path.repo", repositoryPath.getRoot().getAbsolutePath())
                .put("searchguard.enable_snapshot_restore_privilege", true)
                .put("searchguard.check_snapshot_restore_write_privileges", true)
                .build();
    
        setup(settings);
    
        try (TransportClient tc = getInternalTransportClient()) {
            tc.index(new IndexRequest("vulcangov").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            
            tc.admin().cluster().putRepository(new PutRepositoryRequest("vulcangov").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/vulcangov"))).actionGet();
            tc.admin().cluster().createSnapshot(new CreateSnapshotRequest("vulcangov", "vulcangov_1").indices("vulcangov").includeGlobalState(true).waitForCompletion(true)).actionGet();
    
            tc.admin().cluster().putRepository(new PutRepositoryRequest("searchguard").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/searchguard"))).actionGet();
            tc.admin().cluster().createSnapshot(new CreateSnapshotRequest("searchguard", "searchguard_1").indices("searchguard").includeGlobalState(false).waitForCompletion(true)).actionGet();
    
            tc.admin().cluster().putRepository(new PutRepositoryRequest("all").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/all"))).actionGet();
            tc.admin().cluster().createSnapshot(new CreateSnapshotRequest("all", "all_1").indices("*").includeGlobalState(false).waitForCompletion(true)).actionGet();
    
            ConfigUpdateResponse cur = tc.execute(ConfigUpdateAction.INSTANCE, new ConfigUpdateRequest(new String[]{"config","roles","rolesmapping","internalusers","actiongroups"})).actionGet();
            Assert.assertEquals(3, cur.getNodes().size());
            System.out.println(cur.getNodesMap());
        }
    
        RestHelper rh = nonSslRestHelper();
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/vulcangov", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/vulcangov/vulcangov_1", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_$1\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"include_global_state\": true, \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_with_global_state_$1\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","", encodeBasicHeader("worf", "worf")).getStatusCode());
        // Try to restore vulcangov index as searchguard index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"indices\": \"vulcangov\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Try to restore searchguard index.
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/searchguard", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/searchguard/searchguard_1", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/searchguard/searchguard_1/_restore?wait_for_completion=true","", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Try to restore searchguard index as serchguard_copy index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/searchguard/searchguard_1/_restore?wait_for_completion=true","{ \"indices\": \"searchguard\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard_copy\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Try to restore all indices.
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/all", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executeGetRequest("_snapshot/all/all_1", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/all_1/_restore?wait_for_completion=true","", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Try to restore searchguard index as serchguard_copy index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/all_1/_restore?wait_for_completion=true","{ \"indices\": \"vulcangov\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
        // Try to restore searchguard index as serchguard_copy index
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/all_1/_restore?wait_for_completion=true","{ \"indices\": \"searchguard\", \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"searchguard_copy\" }", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Try to restore a unknown snapshot
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/all/unknown-snapshot/_restore?wait_for_completion=true", "", encodeBasicHeader("nagilum", "nagilum")).getStatusCode());
    
        // Tests snapshot with write permissions (OK)
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"$1_restore_1\" }", encodeBasicHeader("restoreuser", "restoreuser")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"$1_restore_2a\" }", encodeBasicHeader("restoreuser", "restoreuser")).getStatusCode());
    
        // Test snapshot with write permissions (FAIL)
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"$1_no_restore_1\" }", encodeBasicHeader("restoreuser", "restoreuser")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"$1_no_restore_2\" }", encodeBasicHeader("restoreuser", "restoreuser")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"$1_no_restore_3\" }", encodeBasicHeader("restoreuser", "restoreuser")).getStatusCode());
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/vulcangov/vulcangov_1/_restore?wait_for_completion=true","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"$1_no_restore_4\" }", encodeBasicHeader("restoreuser", "restoreuser")).getStatusCode());
    }

    @Test
    public void testSnapshotRestore() throws Exception {
    
        final Settings settings = Settings.builder()
                .putList("path.repo", repositoryPath.getRoot().getAbsolutePath())
                .put("searchguard.enable_snapshot_restore_privilege", true)
                .put("searchguard.check_snapshot_restore_write_privileges", true)
                .build();
    
        setup(Settings.EMPTY, new DynamicSgConfig().setSgActionGroups("sg_action_groups_packaged.yml"), settings, true);
    
        try (TransportClient tc = getInternalTransportClient()) {    
            tc.index(new IndexRequest("testsnap1").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("testsnap2").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("testsnap3").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("testsnap4").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("testsnap5").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("testsnap6").type("kolinahr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).source("{\"content\":1}", XContentType.JSON)).actionGet();
            
            tc.admin().cluster().putRepository(new PutRepositoryRequest("bckrepo").type("fs").settings(Settings.builder().put("location", repositoryPath.getRoot().getAbsolutePath() + "/bckrepo"))).actionGet();
        }
    
        RestHelper rh = nonSslRestHelper();        
        String putSnapshot =
        "{"+
          "\"indices\": \"testsnap1\","+
          "\"ignore_unavailable\": false,"+
          "\"include_global_state\": false"+
        "}";
        
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePutRequest("_snapshot/bckrepo/"+putSnapshot.hashCode()+"?wait_for_completion=true&pretty", putSnapshot, encodeBasicHeader("snapresuser", "nagilum")).getStatusCode()); 
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePostRequest("_snapshot/bckrepo/"+putSnapshot.hashCode()+"/_restore?wait_for_completion=true&pretty","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_$1\" }", encodeBasicHeader("snapresuser", "nagilum")).getStatusCode());
        
        putSnapshot =
        "{"+
          "\"indices\": \"searchguard\","+
          "\"ignore_unavailable\": false,"+
          "\"include_global_state\": false"+
        "}";
                
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePutRequest("_snapshot/bckrepo/"+putSnapshot.hashCode()+"?wait_for_completion=true&pretty", putSnapshot, encodeBasicHeader("snapresuser", "nagilum")).getStatusCode()); 
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/bckrepo/"+putSnapshot.hashCode()+"/_restore?wait_for_completion=true&pretty","{ \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_$1\" }", encodeBasicHeader("snapresuser", "nagilum")).getStatusCode());
              
        putSnapshot =
        "{"+
          "\"indices\": \"testsnap2\","+
          "\"ignore_unavailable\": false,"+
          "\"include_global_state\": true"+
        "}";
                        
        Assert.assertEquals(HttpStatus.SC_OK, rh.executePutRequest("_snapshot/bckrepo/"+putSnapshot.hashCode()+"?wait_for_completion=true&pretty", putSnapshot, encodeBasicHeader("snapresuser", "nagilum")).getStatusCode()); 
        Assert.assertEquals(HttpStatus.SC_FORBIDDEN, rh.executePostRequest("_snapshot/bckrepo/"+putSnapshot.hashCode()+"/_restore?wait_for_completion=true&pretty","{ \"include_global_state\": true, \"rename_pattern\": \"(.+)\", \"rename_replacement\": \"restored_index_$1\" }", encodeBasicHeader("snapresuser", "nagilum")).getStatusCode());
    }

}
